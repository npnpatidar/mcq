# Eleventh bug report — scoped pass: the UI layer

A bug-hunting pass over the UI layer — **status:** report only, nothing
fixed yet. Files covered: the test session (`TestSessionScreen.kt`,
`TestViewModel.kt`), study (`StudyScreen.kt`, `StudyViewModel.kt`),
browse (`BrowseScreen.kt`, `BrowseViewModel.kt`, `BrowseWindow.kt`),
library + import (`LibraryScreen.kt`, `LibraryPaperCard.kt`,
`LibraryDrawer.kt`, `ImportScreen.kt`, `ImportViewModel.kt`), stats
screen + charts (`StatsViewModel.kt`, the five tab sections,
`StackedBarChart.kt`, `LineChart.kt`, `HeatmapChart.kt`, `MiniCharts.kt`,
`ChartGeometry.kt`, `ChartKit.kt`), results / history / bookmarks /
search (`ResultsScreen.kt`, `HistoryScreen.kt`, `BookmarksScreen.kt`,
`SearchScreen.kt` and their ViewModels), settings (`SettingsScreen.kt`),
navigation (`McqNavHost.kt`), and the pure helpers behind them
(`TestTiming.kt`, `AutoAdvance.kt`, `Feedback.kt`, `SessionOrder.kt`,
`SubmitSummary.kt`, `DayBoundary.kt`, `Trends.kt`, `StatsAggregator.kt`).

Method: parallel review agents over area slices, then every finding
re-verified by reading the quoted code in this working tree before it
was accepted. Several candidate bugs were rejected at verification
(import double-click guard, progress-bar direction, search debounce
races, score math, chart tap geometry — see "Checked and clean").

**Confidence legend:** **Confirmed** = defect proven by reading the
current code; **(conditional)** = defect real, triggering it needs a
specific window (a recreation while a system dialog is open, a slow
load, a double tap).

---

## Summary

| # | Sev | Area | Bug | Conf. |
|---|-----|------|-----|-------|
| 1 | High | Import | Spurious fatal "Could not read the file" error on rotation or on return from the preview editor — destroys the import session | Confirmed |
| 2 | High | Library | Export scope lives in `remember`; if the activity recreates while the save dialog is open, the picked URI exports the **entire library** instead of the chosen paper/selection | Confirmed (conditional) |
| 3 | High | Test | The countdown never restarts after a failed save — the exam clock freezes and auto-submit is dead while the user keeps answering | Confirmed |
| 4 | Medium | Bookmarks/Settings | Same `remember`-state class: picked file for bookmark export / restore silently ignored after recreation | Confirmed (conditional) |
| 5 | Medium | Browse | Deep-link auto-scroll re-fires on every filter change, yanking the list back to the highlighted question | Confirmed (conditional) |
| 6 | Medium | Results/Search/Bookmarks | Paging `LoadState.Error` renders as a blank list — no message, no retry; append errors unhandled | Confirmed |
| 7 | Medium | Browse | Selection survives filter changes; bulk Delete then acts on rows the user can no longer see | Confirmed |
| 8 | Medium | Test | Scroll position carries over from the previous question into the next one | Confirmed |
| 9 | Low | Browse | `openEditor` clamps an out-of-scope anchor to index 0 instead of reporting "not in scope" | Confirmed |
| 10 | Low | Browse | Selection state in `remember`, not `rememberSaveable` — rotation discards a multi-select mid-flight | Confirmed (conditional) |
| 11 | Low | Browse | Selection export ordered by checkbox-tick order, not list order | Confirmed |
| 12 | Low | Library | Every dialog lives in plain `remember` — rotation closes them and discards typed input | Confirmed |
| 13 | Low | Library | Picked file read inside `rememberCoroutineScope` — a recreation during a large read silently drops the pick | Confirmed (conditional) |
| 14 | Low | Bookmarks/Results | Every bookmark toggle rebuilds the whole pager — a new generation loads from key=null and deep scroll jumps to the top | Confirmed |
| 15 | Low | Bookmarks | "Delete" calls `toggleBookmark` — a double-tap (or a stale row) re-adds the bookmark | Confirmed |
| 16 | Low | Settings | `AnkiNumberRow`: stored value and displayed text diverge (type 1.5 minutes, the field shows 1; stepping writes 2.0 over 1.5) | Confirmed |
| 17 | Low | Settings | Retention slider persists the whole config to DataStore on every drag frame | Confirmed |
| 18 | Low | Bookmarks | Export opens the output stream on the main thread (the Settings sibling correctly hops to IO) | Confirmed |
| 19 | Nit | Test | "No questions in this selection" flashes during session start while the first card resolves | Confirmed |

---

## Findings

### 1 · HIGH · Import — the process-death guard fires on every recreation and kills a live import session

`ImportScreen.kt:178-204` runs `LaunchedEffect(Unit)`, which consumes
`ImportDataHolder.pendingDocxBytes` / `pendingJsonBytes` /
`pendingJsonUri` (the parameters themselves come from reading the same
holder at composition time, `McqNavHost.kt:193-198`) and — when all are
null — calls `viewModel.setNoSourceError()`
(`ImportViewModel.kt:783-790`, "Could not read the file. Please pick it
again.", added in commit a5dd075 against the infinite-spinner
process-death case).

The holder is cleared immediately after the first load
(`ImportScreen.kt:181-197`), but the ViewModel (`key = "import-direct"`,
scoped to the back-stack entry) survives recreation with the parsed
preview intact. So the `else` branch cannot distinguish "process death"
from "holder already consumed". Two concrete flows break:

1. **Any config change** (the manifest has no `configChanges` and no
   orientation lock) while the preview is showing: a modal dialog with
   `onDismissRequest = { }` and a single "Go back" button appears over
   the intact preview; "Go back" pops the screen, so the picked file
   and every field edit are lost.
2. **Tap Edit on a preview question → editor → back:** the route leaves
   composition under standard Navigation Compose disposal and
   recomposes on return; the `LaunchedEffect(currentEntry)` block
   (lines 159-176) applies the edit, then the `Unit` effect immediately
   fires the same fatal dialog — the import becomes unfinishable after
   any single preview edit.

**Fix.** Gate the error on "the ViewModel actually has nothing loaded"
— e.g. `if (viewModel.state.value.questions.isEmpty() && !viewModel.state.value.loading) setNoSourceError()`
— or move the source consumption into the ViewModel's init keyed on a
fingerprint, so recomposition is a no-op while a preview exists.

### 2 · HIGH · Library — stale export state falls through to `exportAll`, silently exporting the whole library

`LibraryScreen.kt:111-117` holds the pending export target in plain
`remember`:

```kotlin
var pendingExportPaperId by remember { mutableStateOf<String?>(null) }
var pendingExportSelection by remember { mutableStateOf<Triple<String, List<String>, Int>?>(null) }
var pendingExportFormat by remember { mutableStateOf<ExportFormat?>(null) }
```

`onExportDocument` (lines 121-137) reads them, clears them, and:

```kotlin
uri?.let {
    if (selection != null && format != null) {
        viewModel.exportSelectionAs(...)
    } else if (paperId != null && format != null) {
        viewModel.exportPaperAs(...)
    } else if (paperId == null && selection == null) {
        viewModel.exportAll(it)
    }
}
```

The format dialog's confirm button (lines 747-768) clears and bails
rather than launching with both null, so `exportAll` has **no
legitimate caller on this screen** — it is reachable only through the
stale-state path: the system "Save to" dialog destroys and recreates
the activity (no `configChanges` in the manifest), the launcher
re-delivers the URI to the new composition where all three vars are
null, and the whole bank is written to the file the user intended for
one paper or one ticked selection. No error, wrong file contents.

**Fix.** Hold the pending target in `rememberSaveable` (a small
parcelable or a `paperId` + branch-id list), or route the choice
through the ViewModel, which survives recreation.

### 3 · HIGH · Test — the countdown is never restarted after a failed save

`TestViewModel.submit()` (lines 691-755) cancels `timerJob` and
`dwellJob` up front. The `SubmissionOutcome.Failed` branch (lines
743-752) resets the screen for a retry:

```kotlin
is com.mcqapp.domain.SubmissionOutcome.Failed -> {
    _state.update {
        it.copy(saving = false, submitted = false, saveError = ...)
    }
}
```

`submitted = false` re-enables the option rows and navigation, so the
user keeps answering — but nothing calls `startTimer()` (its only
callers are `load()`, `resume()` and `discardResume()`). For a timed
paper the clock display freezes at the pre-submit value and the
auto-submit-at-zero deadline is dead for the rest of the sitting; the
recorded `durationSeconds` is likewise frozen
(`totalSeconds - remainingSeconds`, line 703).

**Fix.** Call `startTimer()` in the Failed branch when
`totalSeconds > 0 && remainingSeconds > 0` (it re-anchors from the
current state, so no time is lost), mirroring `discardResume()`.

### 4 · MEDIUM · Bookmarks/Settings — the same `remember`-state class: a picked file is silently ignored

`BookmarksScreen.kt:70-78`:

```kotlin
var showFormatDialog by remember { mutableStateOf(false) }
var exportFormat by remember { mutableStateOf(ExportFormat.JSON_INLINE) }
var pendingFormat by remember { mutableStateOf<ExportFormat?>(null) }

fun onExportDocument(uri: Uri?) {
    val format = pendingFormat
    pendingFormat = null
    if (uri != null && format != null) viewModel.exportBookmarks(uri, format)
}
```

Same shape in `SettingsScreen.kt:105-116, 541-550` (`pendingFormat`,
`restoreUri`, `showRestoreConfirm`). If the activity recreates while
the picker is up, the launcher re-delivers the result to a composition
where `pendingFormat` is null — the picked URI is dropped with no
error and the Settings confirm dialog never appears. Plain rotation
also loses the open dialogs.

**Fix.** `rememberSaveable` (with a string/parcelable for the Uri), or
park the pending choice in the ViewModel.

### 5 · MEDIUM · Browse — the deep-link auto-scroll re-fires after every filter change

`BrowseScreen.kt:305-312`:

```kotlin
val listHasRows = items.itemCount > 0
LaunchedEffect(focusQuestionId, listHasRows) {
    if (focusQuestionId.isBlank() || !listHasRows) return@LaunchedEffect
    viewModel.browseIndexOf(focusQuestionId) { index ->
        if (index >= 0) listState.scrollToItem(index + 1)
    }
}
```

The intent (comment at 297-304) is to retry once the paging window has
rows on first composition. But every filter change goes through
`filters.flatMapLatest { … Pager(…) }` (`BrowseViewModel.kt:201-226`),
which builds a **new** Pager generation: `itemCount` drops to 0 until
that generation's first page lands, then returns to >0 — flipping
`listHasRows` false→true and re-running the effect. After a deep link
lands and the user scrolls elsewhere, tapping any filter chip yanks
the view back to the focus question whenever it is still in scope.

**Fix.** Run the effect once per focus id — e.g. remember a
"scrolled for this id" flag, or key the effect on `focusQuestionId`
only and gate the retry on a generation counter that ignores filter
changes.

### 6 · MEDIUM · Results/Search/Bookmarks — a paging error renders as a blank screen

`ResultsScreen.kt:267-291` only handles the happy empty state:

```kotlin
val refresh = items.loadState.refresh
if (refresh is LoadState.NotLoading && items.itemCount == 0) {
    item(contentType = "empty") { Text("No ${filterValue.lowercase()} questions", ...) }
} else {
    items(count = items.itemCount, ...)
}
```

When `refresh is LoadState.Error`, the `else` branch runs with
`itemCount == 0` — nothing below the filter chips, no message, no
retry. `SearchScreen.kt:101-112` has the same hole (its `when` has no
error branch; the results area goes blank mid-typing), as does
`BookmarksScreen.kt:137-204` (blank list while `state.count > 0`).
Additionally `append is LoadState.Error` is handled nowhere
(`ResultsScreen.kt:292` only checks `Loading`), so a failed page-load
silently stops the list short.

**Fix.** A shared `if (refresh is LoadState.Error) { error row + retry
via items.retry() }` branch in all three screens, and an append-error
footer.

### 7 · MEDIUM · Browse — selection survives a filter change; bulk ops hit hidden rows

`BrowseScreen.kt:106-107` holds selection state; the filter handlers
(`updateFilter`/`updateCategoryFilter`, lines 372 and 407) never touch
it, and the filter chips remain tappable while selection mode is
active. A user who ticks 5 questions and then switches the category
dropdown sees "5 selected" with nothing visibly checked; Delete and
Edit (lines 633, 653-655) then act on the hidden 5. There is no
select-all, so the damage is limited to rows the user explicitly
ticked — but the destructive delete case is easy to trigger.

**Fix.** Prune `selectedIds` to the visible scope on filter change (or
exit selection mode), or show the selection count with an
"includes rows hidden by the current filter" warning.

### 8 · MEDIUM · Test — the question scroll position carries over to the next question

`TestSessionScreen.kt:443-448`:

```kotlin
Column(
    modifier = Modifier
        .weight(1f)
        .verticalScroll(rememberScrollState())
        .padding(16.dp)
)
```

The scroll state is remembered once and never reset when
`state.currentQuestion` changes: reading question 10 scrolled to its
options, tapping Next, and question 11 opens mid-content at the same
offset (long passages make it obvious). A rotation resets it, so the
behaviour is inconsistent between navigation and rotation.

**Fix.** Reset on question change — e.g.
`LaunchedEffect(question.id) { scrollState.scrollTo(0) }`, or key a
`rememberSaveable` scroll state by question id.

### 9 · LOW · Browse — an out-of-scope anchor is clamped to index 0

`BrowseViewModel.kt:516-528`:

```kotlin
val index = repository.browseFilteredIndexOf(anchorId, scope).coerceIn(0, size - 1)
```

`browseFilteredIndexOf` returns -1 when the question is not in the
current filter — the sibling `browseIndexOf` (lines 502-509) documents
that and double-checks with `browseFilteredIdAt`. `openEditor` has no
such check: editing a question the active filter hides yields a
Prev/Next cursor anchored at position 1-of-M, so the editor's "N of M"
indicator and next/prev navigation start from the wrong place for that
session.

**Fix.** When the index is -1, pass a cursor without an anchor (or
surface "not in current filter") instead of coercing.

### 10 · LOW · Browse — selection state is lost on rotation

`BrowseScreen.kt:106-113` — `selectionMode`, `selectedIds` and
`exportIds` are plain `remember`. Any config change discards a
multi-select mid-flight while the ViewModel keeps the filter state;
the list looks unchanged but the selection silently disappears.
`exportIds` is captured before the SAF picker opens, so process death
while the picker is up makes `onExportDocument` (lines 125-129)
no-op silently (`ids.isNotEmpty()` false).

**Fix.** `rememberSaveable` for the id set (it is a `Set<String>` —
save as `ArrayList<String>`).

### 11 · LOW · Browse — selection export is ordered by tick order

`BrowseViewModel.kt:622-623`:

```kotlin
val selected = repository.getQuestionsByIds(questionIds.toList())
    .sortedBy { q -> questionIds.indexOf(q.id) }
```

`selectedIds` is a `LinkedHashSet` built in tick order (re-ticking a
removed id appends it at the end), so a multi-select export of one
paper comes out in the order boxes were ticked rather than the browse
order the user sees. A whole-paper export sorts properly; the
selection path does not.

**Fix.** Order by the browse scope's own ordering (`browseFilteredIndexOf`
or the paper's flat order) instead of tick order.

### 12 · LOW · Library — every dialog is composition state

`LibraryScreen.kt:101-117` (paper dialog, category dialog, test-start
dialog, export state), `LibraryPaperCard.kt:124-126` (drill dialog
fields, `drillCountText`/`drillMinutesText`), `LibraryDrawer.kt:82`
(`expandedPaperId`) — all plain `remember`, none `rememberSaveable`.
A single rotation closes every open dialog and discards typed input
(a half-typed paper title, drill counts). The VM-backed dialogs
(delete confirmation, import report, export error) survive — the
asymmetry shows this is accidental.

**Fix.** `rememberSaveable` for the flags and the `Paper?`/id fields
(ids are saveable; `Paper?` needs a saver or an id + lookup).

### 13 · LOW · Library — the pick is read in the composition scope

`LibraryScreen.kt:98` creates `rememberCoroutineScope()`, and the
import picker callback (line 175) launches the bounded prefix read +
zip classification + DOCX read (cap 64 MB) in it. A recreation during
that read cancels the coroutine with no error and no navigation — the
pick just evaporates. The apkg path already runs through the
ViewModel scope; this one does not.

**Fix.** Hand the Uri to `LibraryViewModel` and read there (survives
recreation), like the apkg flow.

### 14 · LOW · Bookmarks/Results — every bookmark toggle rebuilds the pager

`BookmarksViewModel.kt:39-52`:

```kotlin
repository.observeBookmarkChanges()
    .flatMapLatest {
        Pager(...) { repository.bookmarkedPagingSource() }.flow
    }
    .cachedIn(viewModelScope)
```

Each table change emits through `flatMapLatest`, creating a new Pager
generation. A new generation's initial load is `Refresh(key = null)`
from the top — the anchor-preserving `getRefreshKey`
(`HistoryStore.kt:583-596`) only applies to same-generation
invalidation, which this path bypasses. Scrolled deep into a long
bookmarks list, removing one item swaps in a snapshot of only the
newest page and the list jumps back to the top. Same class in
`ResultsViewModel.kt:78-90` on the "Saved" chip (every bookmark
change reissues `request` → new Pager).

**Fix.** Let Room invalidation drive the same Pager generation
(`bookmarkedPagingSource()` is already invalidation-aware) instead of
swapping Pagers through `flatMapLatest`.

### 15 · LOW · Bookmarks — "delete" is a toggle

`BookmarksViewModel.kt:63-65`:

```kotlin
fun removeBookmark(questionId: String) {
    viewModelScope.launch { repository.toggleBookmark(questionId) }
}
```

The transaction makes each call atomic, but two sequential calls still
flip twice: tap 1 removes, tap 2 (row still composed before the pager
rebuild) reads `wasBookmarked=false` and re-adds — the item the user
deleted reappears. A row un-bookmarked elsewhere between render and
tap is likewise un-deleted.

**Fix.** A `deleteBookmark` (idempotent remove) on this screen.

### 16 · LOW · Settings — `AnkiNumberRow` display and stored value diverge

`SettingsScreen.kt:963-972`:

```kotlin
var text by remember(current) { mutableStateOf(formatValue(current, step)) }
...
return if (decimals == 0) value.toInt().toString()
```

(a) "Relearning delay" has `step = 1.0` (line 872) but the value is
`relearnMs / 60000.0` (line 870). Type `1.5` → `onChange(1.5)` stores
`relearnMs = 90000`; the flow round-trips, `current` becomes 1.5, and
`formatValue(1.5, step = 1.0)` truncates to `"1"` — the field shows 1
while 1.5 minutes is stored, and stepping from it writes 2.0 (120000)
over 90000.

(b) `SpacedRepetition.kt:204` clamps
`slowSeconds = slowSeconds.coerceIn(fastSeconds.coerceIn(1, 3600), 3600)`.
With fast = 100, typing 50 into "Hard answer threshold" (range claims
1..3600, lines 808-816) is accepted, then `sanitized()` snaps it to
100 and `remember(current)` rewrites the text mid-edit — the field
silently fights the user with no hint that slow ≥ fast is enforced.

**Fix.** (a) Step in 0.5 for the minutes rows or store seconds with a
step-1 field; (b) validate slow ≥ fast in the row (disable Apply, like
the marks flow) instead of sanitizing behind the user's back.

### 17 · LOW · Settings — the retention slider writes DataStore per drag frame

`SettingsScreen.kt:652-658` → `SettingsViewModel.kt:192-195` →
`RepositorySettings.kt:200-214`:

```kotlin
onValueChange = { onChange(config.copy(desiredRetention = it.toDouble())) }
...
fun updateSchedulerConfig(config: ...) {
    _schedulerConfig.value = config.sanitized()
    viewModelScope.launch { repository.setSchedulerConfig(config) }
}
```

`onValueChange` fires per frame during a drag; each fires a DataStore
edit (full file rewrite + fsync, serialized) — tens of writes where
one write on release would do. The text rows' per-keystroke commit is
deliberate ("in-progress value and stored value never drift"); the
slider gains nothing from it.

**Fix.** Commit from `onValueChangeFinished` only (keep the local
`onValueChange` for display), or debounce the repository write.

### 18 · LOW · Bookmarks — export opens the output stream on the main thread

`BookmarksViewModel.kt:74-90`: the whole `exportBookmarks` runs in
`viewModelScope.launch` (Main.immediate);
`contentResolver.openOutputStream(uri)` (line 79) is a blocking binder
call before `PaperExporter` hops to IO. Slow/cloud documents providers
can jank or ANR. The Settings sibling deliberately wraps the identical
call in `Dispatchers.IO` (`SettingsViewModel.kt:324, 351`) — a missed
hop, not a design choice.

**Fix.** `withContext(Dispatchers.IO)` around the open + export, as in
Settings.

### 19 · NIT · Test — "No questions in this selection" flashes at session start

`TestViewModel.load()` publishes `loading = false` (line 291-306)
before `resolveCurrent()` (line 312) — which resolves the question
asynchronously through a DB read (`resolveCurrent` → `launch {
loadCurrent }`). Between those, `TestSessionScreen.kt:432-441` renders
its `question == null` branch: the "No questions in this selection"
message flashes for a frame or two on every session start (and after
retry) before the first card lands.

**Fix.** Treat `current == null && order.isNotEmpty()` as still
loading (or keep `loading` true until the first publish).

---

## Checked and clean

Areas hunted with no confirmed findings:

- **Study flow** (`StudyScreen.kt`, `StudyViewModel.kt`): grade
  double-tap guarded by a synchronous `grading` claim; clock
  pause/resume gating (`clockMayRun`) consistent between the
  viewport effect, backgrounding and the user pause; guess flag
  locked at reveal and reset per card; break-strip milestone math;
  restart clears the stale card before the pull.
- **Stats aggregators + charts** (`StatsAggregator.kt`,
  `StatsViewModel.kt`, chart files): heatmap draw index (`index/7`,
  `index%7`) matches the tap mapping (`col*rows + row`, with the
  label-strip negative-row guard); bar tap index guarded to
  `points.indices`; all histogram edge/label counts line up
  (6 edges/7 labels etc.); calibration bins clamped to 0..9; the
  generation counter drops superseded builds; streak logic treats an
  unfinished today correctly; `yTicks`/`niceMax` guarded against
  zero/empty. (Pre-existing stats findings live in `stats_bugs.md`
  and are not repeated here.)
- **Test session maths**: the wall-clock countdown anchor
  (`Countdown.remainingAt`) cannot drift or go negative; `TimerWarnings`
  fire once per threshold; dwell flush/pause bank the active segment
  without losing sub-second remainders; `AutoAdvance` stops at the
  last question and never advances multi-correct; option-shuffle seed
  is per-question stable (`seed xor id.hashCode`); the submit
  summary's slowest question folds in the live dwell before comparing.
- **SessionOrder**: `idAt` bounds-safe via `idAtOrNull` at every call
  site; the palette's `items(count, key = idAt)` cannot exceed size;
  `reorderedTo` guards duplicate ids; block shuffle/cut preserve
  passage blocks with the documented overshoot.
- **Results**: score math in `Double` matching the format strings;
  percentage guarded by `maxScore > 0`; paging cursor carries the
  unfiltered index through filtered scans (no skip/duplicate); full
  rows re-fetched by id so the review list cannot show a stale
  question.
- **Search**: `debounce(300)` + `distinctUntilChanged` +
  `flatMapLatest` converges to the last input; blank query
  short-circuits; `SELECT DISTINCT … rowid` keeps `itemKey` unique.
- **ReviewFilters / Reorder / BulkOps / QuestionSearch**: the five
  states partition exactly (SQL mirror matches), the reorder clamps
  safely, bulk renames terminate and avoid collisions, the LIKE
  prefilter is a superset of the Kotlin filter.
- **Import internals**: streaming progress is monotonic; success and
  error end-states reached in every branch; the import button is
  disabled synchronously during flight; URI grants need no
  persistence (all reads in-session); LazyColumn keys are correct and
  disjoint; one-shot report/error events ride StateFlow, so nothing
  is dropped on config change.
- **Settings switches/sliders**: every toggle writes via the
  repository and reads back via flow; `sanitized()` runs before
  persist; the retention slider's 0.01 steps match the clamp.
