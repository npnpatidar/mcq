# Third bug hunt — findings against `main` @ HEAD

**Scope:** production Kotlin under `app/src/main/java/com/mcqapp/`, focused on the code that
changed since the second pass (`f21afc7..HEAD`) — category-scoped study/test/export, the
category picker and counts, the chunked category queries, the paper card, the browse
paging/filter changes — plus a re-check of the earlier docs' fixes and deferred items.

Every claim below was re-read against the current source in this pass; two candidate
findings drafted along the way were **dropped after checking the code** (noted at the end).
Severity/Certainty as in the previous docs.

**Verification pass (2026-10-22):** every finding and every 'clean' claim was re-checked
against the source independently and this doc was updated to match what was found:
finding #1 was rewritten (its central premise was contradicted by the code), #5's
mechanism note was corrected, #7 was sharpened, and several stale line references were
fixed. The 'clean' sections all held.

---

## Findings

### 1 · Low · Study and Test card order can disagree — but only past the chunk wall

*(Rewritten by the verification pass. The original draft claimed Study serves the
selection in tree pre-order and scored this Medium. That premise is wrong; the code
facts it cited are all real, so the residual issue is kept at Low.)*

`StudyViewModel.kt:201-205` and `TestViewModel.kt:209-213` both expand the ticked ids with
`CategorySelection.orderedIds(...)` (`CategorySelection.kt:73-89`), which walks the tree in
**pre-order**. But the expansion only decides *which* categories are in scope, not the
order the cards come out in:

- **Study** (`StudyStore.getStudyQueue`, `StudyStore.kt:93-135`) feeds `dueCards` and
  `newQuestions` (`StudyStore.kt:204-230`), whose SQL orders rows by
  `(c.sortOrder, c.title, q.sortOrder, q.rowid)` inside each chunk
  (`Daos.kt:588-590` new, `Daos.kt:1552-1553` due), chunked at 500 ids
  (`ChunkedQueries.kt:15`).
- **Test** — `QuestionStore.getOrderForCategories` (`QuestionStore.kt:122-128`), backing
  `TestViewModel` → `repository.getSessionOrderForCategories` — re-sorts the passed list
  flat by `(sortOrder, title)` in Kotlin, then loads each category in that order: the
  same flat sort, end to end.

So for any scope of **≤ 500 in-scope categories both screens present the same relative
category order**, whatever the orderedIds expansion is. The expansion never reaches the
user as an order on Study's side except across chunk boundaries.

Divergence therefore needs a scope **over 500 categories** crossing a chunk wall where
cross-chunk pre-order differs from the flat sort — and the app cannot shape such a tree:
every tree-shaping edit (`moveCategory`, `nestCategory`, `promoteCategoryOutOfParent`,
`reparentCategory`) ends in `renumberPaperPreorder` (`PaperStore.kt:304-317`, called at
`:249`, `:333`, `:354`, `:394`), which rewrites `sortOrder` as the pre-order position, so
after any in-app edit the flat sort *equals* the tree order. Only an imported paper that
carries an arbitrary `(parent, sortOrder)` matrix and more than 500 in-scope categories
can diverge.

Implication for the fix: "expand with `orderedIds` in `getOrderForCategories`" alone is
**not** enough — Study would still re-sort each chunk by `(sortOrder, title)`, so the two
orders would only match at ≤ 500 categories. Unifying means one order contract for both:
chunked pre-order for both, or flat `(sortOrder, title)` for both with Study's chunk
boundaries aligned to the same sort.

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

`BrowseViewModel.kt:136-145` (the earlier draft's `97-103` is the `BrowseFilterState` data
class): `filters`' initial value and `filterState`'s
`SharingStarted.Eagerly` initial value both carry
`BrowseFilterState(categoryFilterIds = initialCategoryFilterIds.toSet())`. They must stay in
sync; today they are written adjoined within 6 lines, so it is a drift trap, not a bug. One
shared `val initialFilterState = …` would close it.

### 5 · Low · `BrowseViewModel.loadContent`'s prune — no bug; the earlier mechanism note was wrong

`BrowseViewModel.kt:344-385`. **No bug confirmed here.** Correction from the verification
pass: the earlier note claimed an asymmetry — "only the right-hand `loaded.filterKeys` is
taken from the lambda's window and the left-hand filter uses the LIVE `windowIds`". That
is inaccurate: both `filterKeys { it in windowIds }` lambdas (left, pruning held cards;
right, filtering the rows this read loaded) read the live `windowIds` member each time the
`_state.update` lambda runs, so there is no stale snapshot on either side. There is no
"this very coroutine's windowIds" pitfall for a reader to fear. The source's own inline
comment ("only what the current window covers is kept…", "Pruned with the cards they
describe") remains the best description of the mechanism and the out-of-order-read
justification.

### 6 · Low · `BrowseViewModel.pagingConfig` constant `jumpThreshold = BROWSE_PAGE * 3` evaluates to `180`

Cosmetic only (verified: `BROWSE_PAGE = 60` at `BrowseViewModel.kt:680`, the
`jumpThreshold = BROWSE_PAGE * 3` expression at `:714`): the compose diamond is
intentionally the same as the docstring's "three pages", but a future reader tuning
`BROWSE_PAGE` from 60 changes the jump threshold implicitly. Could be a named constant
pair if touched again.

### 7 · Low · `statsReviews()` is a whole-log materialiser with no production caller

Unchanged from the carry-over, sharpened by the verification pass: `McqRepository.kt:128`
exposes `statsReviews()`, which loads the whole replay log via `StatsStore.loadReviews()`
(`StatsStore.kt:56`). **Nothing in production calls it** — its only callers anywhere are
three test files (`StatsTabsStreamTest`, `StatsRetentionStreamTest`,
`StatsOverviewStreamTest`), so the earlier draft's "any tab that still calls the old
accessor forces the full log into memory once per tab visit" was hypothetical: no such
tab exists at HEAD. The per-tab streamed variants (`statsRetention`, `statsOverview`,
`statsMemory`, `statsCards`, `statsWorkload`) are wired in the same object and cover every
tab. It is a dead production accessor kept alive by tests: prune it, or move those tests
onto a streamed variant if they still need the raw log.

### 8 · Low · `PickedArchive.classifyPickedArchive(input)`'s count check `checked < DOCX_SCAN_ENTRIES` counts entries, not files

`PickedArchive.kt:44-104` (the earlier draft said `19-100`) — `DOCX_SCAN_ENTRIES = 12` is a
cap on archive entries examined,
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
