# Third bug hunt — findings against `main` @ HEAD

**Scope:** production Kotlin under `app/src/main/java/com/mcqapp/`, focused on the code that
changed since the second pass (`f21afc7..HEAD`) — category-scoped study/test/export, the
category picker and counts, the chunked category queries, the paper card, the browse
paging/filter changes — plus a re-check of the earlier docs' fixes and deferred items.

Every claim below was re-read against the current source in this pass; two candidate
findings drafted along the way were **dropped after checking the code** (noted at the end).
Severity/Certainty as in the previous docs.

---

## Findings

### 1 · Medium · Study and Test present the same scoped selection in different orders

`StudyViewModel.kt:201-205` expands the ticked ids with
`CategorySelection.orderedIds(...)` (`CategorySelection.kt:73-89`), which walks the tree in
**pre-order** — each ticked subtree appears as its in-order run, and the selection's
categories are served in tree shape.

`QuestionStore.getOrderForCategories` (`QuestionStore.kt:122-128`), which backs the scoped
**test** session (`TestViewModel` → `repository.getSessionOrderForCategories`), reads the
ticked ids as a flat list and sorts by `(sortOrder, title)` in Kotlin, then loads each
category in that order.

Neither is wrong on its own, but the two orders can disagree for the same scope: a category
whose authored `sortOrder` places it after one of its own descendants (possible for imported
files that carry an arbitrary parent/sortOrder matrix, or after cross-nesting edits the app
allows with `reparentCategory` — where the pre-order walk and the flat sort order a ladder
differently). The user ticks two branches, studies them, then runs the drill for the same
selection, and sees the categories in a different relative order between the two screens.

Pick one and use it in both: simplest is to expand with `orderedIds` in
`getOrderForCategories` as well, or replace the Kotlin `(sortOrder, title)` pre-sort with the
paper-order walk.

### 2 · Medium · Editor-attached images silently recompressed to opaque JPEG

`QuestionEditorScreen.kt:88-114` picks with `GetContent` and stores
`ImageUtils.encodeImageUri(context, uri)`; `ImageUtils.kt:17-38` decodes, EXIF-uprights, then
`compress(Bitmap.CompressFormat.JPEG, 80, …)` unconditionally.

Consequences for a picked PNG (a diagram, chart or screenshot — exactly what a question
author attaches):
- **transparency is lost** — a JPEG has no alpha channel, so transparent regions render
  black/dark on export, PDF and DOCX;
- **lossy re-encode on pick** — the pick's own format and quality are discarded, so a crisp
  PNG is recompressed at quality 80 whatever it was (`MAX_DIMENSION` down-sizing itself is
  reasonable; the format/quality choice is not).

The import paths (Anki, DOCX, JSON) store original bytes verbatim, so an editor-attached
image degrades relative to the same image imported with a paper. Fix at the format layer:
encode to the source's format (keep `PNG` when the source bytes say PNG), or store the
picked bytes as-is when they are already within the cap (`readBounded` gives you them) and
exif-neutral.

### 3 · Low-Med · New paper-card surface is full of hardcoded user-facing strings

`LibraryPaperCard.kt:243-246` ("Study selection (N due…/… new)"), `:267` ("N tricky"),
`:299` ("Mistakes (N)"), and `:63-69` (the `${counts.leeches} tricky` badge word). All
verified at HEAD. Meanwhile other files in this very commit range do use resources for user-
visible copy (`stringResource(R.string.back)` in this same screen's flow,
`R.string.duration_minutes_0_untimed` in `LibraryDialogs.kt:64`). Sweep the card's copy
into resources, or the next localisation pass will miss it.

### 4 · Low · `BrowseViewModel.filterState` initial-state duplication

`BrowseViewModel.kt:97-103`: `filters`' initial value and `filterState`'s
`SharingStarted.Eagerly` initial value both carry
`BrowseFilterState(categoryFilterIds = initialCategoryFilterIds.toSet())`. They must stay in
sync; today they are written adjoined within 6 lines, so it is a drift trap, not a bug. One
shared `val initialFilterState = …` would close it.

### 5 · Low · `BrowseViewModel.loadContent`'s prune drops rows that the window has not covered yet

`BrowseViewModel.kt:247-253` keeps cards for `it in windowIds` — the CURRENT window —
but the map update writes `current.contentById.filterKeys { it in windowIds } + loaded…`,
so a card whose row is in `onVisibleRows`'s window but was pruned by **this very coroutine's**
`windowIds` snapshot taken at entry: reads that started before a scroll advance and finish
afterwards stay correct because `windowIds` is re-read on every `_state.update` —
**only the right-hand `loaded.filterKeys` is taken from the lambda's window and the
left-hand filter uses the LIVE `windowIds`**, a good design. The `contentHashes.keys.retainAll(windowIds)`
returns `false` (nothing to report when nothing was dropped) but is
never checked; that is fine. **No bug confirmed here** — leaving the mechanism's note for a
future reader because the inline justification ("Pruned with the cards they describe…")
was the one paragraph this pass could not improve on.

### 6 · Low · `BrowseViewModel.pagingConfig` constant `jumpThreshold = BROWSE_PAGE * 3` evaluates to `180`

Cosmetic only: the compose diamond is intentionally the same as the docstring's "three
pages", but a future reader tuning `BROWSE_PAGE` from 60 changes the jump threshold
implicitly. Could be a named constant pair if touched again.

### 7 · Low · `statsReviews()` remains a dead whole-log materialiser for callers that never call it

Unchanged from the carry-over: `McqRepository.kt:74-77` still exposes `statsReviews()`
loading the whole replay log for the tabs that "still slice it in memory" — the per-tab
streamed variants (`statsRetention`, `statsOverview`, `statsMemory`, `statsCards`,
`statsWorkload`) are wired in the same object; any tab that still calls the old accessor
forces the full log into memory once per tab visit. Grep at HEAD confirms `statsReviews`
is a live accessor, and it is the path worth pruning next.

### 8 · Low · `PickedArchive.classifyPickedArchive(input)`'s count check `checked < DOCX_SCAN_ENTRIES` counts entries, not files

`PickedArchive.kt:19-100` — `DOCX_SCAN_ENTRIES = 12` is a cap on archive entries examined,
and directories count toward it (`checked++` increments on every entry regardless of
`isDirectory`). A docx with a chunky `[Content_Types].xml`… `_rels/…`… docProps cluster
can push `word/document.xml` past entry 12 in a pathological-but-real re-zip, in which case
the file is classified as `OTHER` (i.e. handed to the Anki reader, which mis-answers with
an Anki-shaped error instead of the "this is a Word bank" message). Low-probability byte
layout, but the fix is free: count only non-directory entries.

---

## Clean at HEAD (earlier docs' fixes verified in place)

- `new_bugs.md` #1 (Anki `revlog.time` mis-read as timestamp): `AnkiScheduling.kt:97` and
  its caller no longer multiply a duration column; the ms `id` column is used directly.
- `new_bugs.md` #27 (`INSTR` on raw category ids leaking comma-containing ids):
  `CategoryDao.rowIdsByIds` (`Daos.kt:70-77`) + `McqRepository.categoryRowIdCsv`
  (`McqRepository.kt:555-560`) binds numeric rowids (chunked at 900); the SQL consumes them
  with `INSTR` on `,rowid,` (`Daos.kt:673`), which commas cannot spoof.
- Over-999 bind wall for scoped queries: `ChunkedQueries.kt:170-201` chunk-splits
  `firstDueLearning`, `countForCategories`, `firstNewQuestion`, `newQuestions`.
- 40 MB pick-at-once (photo import): `ImageUtils.encodeImageUri` now `readBounded`s the
  input and `uprightedBitmap`s it (EXIF fix kept, bitmap recycled).
- `PaperStore.reparentCategory` (`PaperStore.kt:367-398`) does validate cross-paper and
  cycles (`newParent.paperId != category.paperId`, `subtree(categoryId)` sweep) before
  writing — an earlier draft of this doc suspected it did not; the code is right.
- `CategorySelection.questionCount` (`CategorySelection.kt:100-116`) does not double-count a
  non-maximal ticked pair: `CategoryNode.questionCount` is the node's direct count
  (`Models.kt:297`), and the walk adds each in-scope node's direct count exactly once.

## Verified clean (this pass, no deltas)

- `PickedArchive.kt` exception-swallow-and-answer-from-prefix read (matches the byte-check
  path's documented behaviour, no regression).
- `Routes.kt` (whole file): every id is percent-encoded at build time, and
  `testRoute`'s empty `categories=`+`mistakes=true` combo round-trips as unscoped+ mistakes
  as intended.
- `AppDatabase`/`DaoModel` migration plan unchanged, `chunked` DAO inventory consistent
  with `McqRepository`'s imports.
- `CategoryCounts` roll-up (`CategoryCounts.kt`): post-order sum, pre-order re-emit,
  scope-total from maximal ticked ids — consistent with `CategorySelection.tickedIds`'
  documented contract; no double-count.

## Dropped after verification (recorded so later passes do not re-chase them)

- *Suspected:* `PaperStore.reparentCategory` accepting a cross-paper parent. Disproved:
  `PaperStore.kt:374` refuses it.
- *Suspected:* `questionCount` double-counting a non-maximal ticked input. Disproved:
  `CategoryNode.questionCount` is direct-only, so the walk is exact.
