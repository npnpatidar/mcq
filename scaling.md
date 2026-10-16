# Scaling plan: every screen at hundreds of thousands of questions

Goal: the app stays smooth (no jank, freeze, hang, crash, OOM) with papers of
100k–500k+ questions, while every existing feature keeps behaving exactly as
it does today. Nothing here changes what the user can do; it changes how much
data any single operation is allowed to touch at once.

Written from the actual hot paths (files and problems are named per screen)
and from how Anki/AnkiDroid survive collections that large.

---

## 1. Ground rules (learned from Anki, applied here)

References:

- **AnkiDroid never runs "massive queries and processes them in memory"; it
  does "queries as required per note"** (AnkiDroid issue #2547, "Deleting
  decks and importing cards from apkg is too slow"). This is the single most
  important rule, and this codebase already follows it in its best paths
  (`QuestionRef`, chunked queries, per-question content fetch). Every
  violation below is a place where a screen still asks for everything.
- **Anki got big-collection wins by lazy loading** (ankitects/anki #5171
  "lazy-loading MathJax" for reviewer init; Anki 2.1.50–59 "Improved
  performance with large selections in the Browse screen"). Anything a screen
  *may* show but is not showing right now must not be loaded, parsed or
  hashed.
- **The reviewer prefetches only the next card** (Anki's card queue). The rest
  of the day's work is derived from indexes and counts, never materialised.
- **Android/Room**: list surfaces page through `room-paging` `PagingSource`
  (invalidation is automatic), heavy aggregation belongs in SQL (`GROUP BY`
  over indexed columns), and WAL journal mode keeps readers non-blocking
  while a write is in flight.

Constraints in this repo:

- Features stay as-is: same buttons, same orderings, same filters, same FSRS
  semantics, same Anki interop contract.
- No test dependency additions; no SQLite driver library (project decision).
- Linux aarch64 cannot run Robolectric SQLite tests locally; CI (x86_64) is
  authoritative. Perf gates must therefore be pure-JVM where possible.

Measurable definition of "smooth" (each screen's section ends with targets):

| Metric | Target at a 250k-question paper |
|---|---|
| Cold list first frame (Browse / test palette) | < 800 ms, rows streamed |
| Keystroke → first results (search) | < 350 ms p95 |
| Tap → next/prev cards | < 120 ms p95 |
| Long frames while scrolling a list | 0 |
| Peak heap importing a 100k bank | < 60 % of max heap |
| Submit a 250k attempt | streamed, no ANR, no OOM |
| Study pull (next card) | < 150 ms p95 |
| Stats open with 1 M review_log rows | < 3 s first paint |

---

## 2. What is already in place (keep; do not regress)

- `QuestionRef` (`data/local/Daos.kt`): list surfaces order ids + preview
  stems only; content is fetched per on-screen question. This is the app's
  Anki-style "queries as required" backbone.
- `ChunkedQueries.kt`: every `IN (:ids)` is chunked under the 999-bind cap
  (API 26). Extend it; never bypass it.
- `HistoryStore.saveAttemptStreamed`: attempt reads/writes in 500-row batches
  inside one transaction.
- `StudyStore.getNextStudyCard` + `Study.eligibility`: pull-based session, no
  prebuilt queue.
- `TestViewModel`: resolves only the on-screen question; review sheet built in
  batches; streamed save on submit.
- `Importer`: one collision-candidate prefetch, image downscale outside the
  write transaction, chunked matchers, truncated log lines for big files.
- UI: `LazyColumn` everywhere, hoisted `remember` work, block-aware drill
  sampling (`PassageBlocks`), dwell/time-loop states split so a 1 s tick does
  not invalidate whole question cards.

---

## 3. Foundations (do these first; every screen rides on them)

### F1. Finish the half-migrated Browse branch (P0 correctness gate)

`BrowseScreen.kt` and `BrowseViewModel.kt` currently reference a
`questions` property that no longer exists on `BrowseUiState`
(`BrowseScreen.kt:232`, `BrowseViewModel.kt:172` in the working tree; the
state carries `refs`). Verify with `:app:assembleDebug` on CI and finish the
`List<Question>` → `List<QuestionRef>` migration in:

- `BrowseViewModel.exportSelection` — it must re-resolve the selected ids
  through `getQuestionsByIds(...)`, not filter a stale full-content list.
- Anything else in the two files still touching pre-migration fields.

No scale work lands until the branch compiles and screens render from refs
end to end.

### F2. Index the queries that scan today

`Entities.kt` has no index on `questions.contentHash`, yet the dedup path
(`getMatchesByContentHashesChunked`, `getIdByContentHash`) probes it for every
import and every preview refresh → a full table scan per chunk today. Add:

- `questions(contentHash)` — import dedup, duplicate classification.
- `card_state(paperId, cardState, dueAt)` — per-paper due counts in one
  grouped query (Library badges, study eligibility).
- `review_log(paperId, reviewedAt)` — per-day heatmap aggregates.
- `question_results(attemptId, isCorrect)` — results-screen tallies.
- `papers(title)` and `categories(paperId, title)` — title-match imports and
  the `(sortOrder, title)` ordering used by `getByCategories`.

Ship as migration v11 with `exportSchema = true` from now on so schema drift
is reviewed.

### F3. Room housekeeping

- `AppDatabase.get(...)`: `.setJournalMode(WAL)` — imports and bulk writes stop
  fighting readers (browse/test/search screens observe the DB while an import
  writes).
- Delete or confine `QuestionDao.observeAll()` / `getAll()`-style
  whole-table `SELECT *` queries: at 250k questions with element JSON these
  are an OOM waiting for a caller. Keep only column-narrow variants
  (`getIdCategories`, hashes-only, etc.).
- Keep `exportSchema = true` + a schema-version guard test (there is already a
  `VersionCodeGuardTest` habit) so indexes/tables survive future migrations.

### F4. Search-text column (FTS) instead of LIKE over element JSON

`questions.text` stores kotlinx-serialised element JSON with markup, so
`LIKE '%' || :q || '%'` on it is both slow (no index possible) and semantically
mushy (that is why `QuestionSearch.prefilterKey` exists). Add, at save time:

- `questions.searchText` — plain flattened text of stem + tags (the same
  `textContent`/`searchableText` output the in-memory filters already use),
  refreshed in `saveQuestion`, `Importer`, `updateBulkFields`, `rename` paths.
- A `@Fts4` shadow entity over `searchText` (Room supports `@Fts4` directly).
- `questions.searchText` indexed (FTS is), and the DAO search switches from
  LIKE to `searchText MATCH` for the stem/tags/option/passage hit union.

Backfill the column in migration v11 with a plain string built off
`parseContentElements(...).searchableText` (batched, off the main thread).
This is the enabler for Search (§4.6), Browse in-paper search (§4.1) and the
browse-screen in-memory filter less clearly needed at all.

### F5. Streaming exports

`BrowseViewModel.exportSelection` / `BookmarksViewModel.exportBookmarks`
build the whole export in memory as `result.bytes` before writing. A 100k
selection is tens of MB. Change `PaperExporter` (and `AnkiPackageWriter`) to
accept an `OutputStream` and write progressively: JSON gets a segmenting
writer, PDF/DOCX already stream from POJOs. Same for the backup writer in
`Backup.kt`. Feature set unchanged; memory ceiling removed.

### F6. Logging cost under bulk work

`Importer` logs several lines *per question*. At 100k questions that is
~500k string builds and I/O inside the transaction loop. Gate per-question
logs behind a verbose flag (keep the batch summary lines), so logging cost is
constant, not O(file size).

---

## 4. Screen-by-screen plan

### 4.1 Browse

**Today's hot paths** (`ui/browse/BrowseScreen.kt`, `BrowseViewModel.kt`,
`QuestionStore.observeRefsForPaper`):

- Whole-list refetch: the observer re-runs `getRefsByCategoriesChunked` for
  the whole paper on every category/question write. At 250k that re-emits
  hundreds of thousands of `QuestionRef` objects (`text` + `explanation`
  stems included) after every save/delete/renumber.
- Main-thread filtering: `searched = questions.filter { ref ->
  ref.text.contains(...) }` runs *during composition* (`remember` block) and
  scans every stem per keystroke. This is the freeze at scale.
- `questionContent(questionId)` fetches the *full* question (options, key,
  explanation, image) for every row as it composes — full scanning makes each
  row's first climb expensive and momentarily heavy.
- `EditorSession.start(ids = filteredIds, ...)` hands the editor a full
  List<String> of paper ids (~5–27 MB) just to support prev/next.
- Passage bodies for the filter are all preloaded (`getPassagesForPaper`).

**Plan**

1. Refs → **Paging 3**:
   - `QuestionDao` gets a `PagingSource` variant of `getRefsByCategories`
     (keyset on `(categories.sortOrder, categories.title, questions.sortOrder,
     rowid)` room-paging style so the ordering is preserved).
   - `BrowseViewModel` exposes `Pager(PAGING_SIZE = ~60)` +
     `cachedIn(viewModelScope)`.
   - Screen keeps the LazyColumn, swaps `itemsIndexed(filtered, ...)` for
     `LazyPagingItems`. Item count for the header/summary comes from a cheap
     `COUNT(*)` query, not from materialising the list.
   - Deletion/move/etc. invalidation comes free from Room's invalidation
     tracker (same refetch-on-write behaviour the screen relies on today).
2. Move search off the main thread and into SQL:
   - In-paper search uses the FTS column (F4): `MATCH :query` constrained by
     `categoryId IN (...)`, chunked, emitting a `PagingSource` keyed identically.
     - falls back to LIKE on `searchText` on devices where FTS works fine
       (it always does; LIKE remains only as a migration fallback).
   - The "No answer"/"No explanation"/"Uncategorized" chips become WHERE
     predicates in the same `PagingSource` (`answerKeyCount = 0` is a
     correlated subquery today; keep the semantics, execute it in SQL).
   - The category dropdown filter adds `categoryId = :id` (subtree id list
     resolved in the ViewModel, chunked).
3. Reorder-controls math stays, but computed on **window data + cached
   context**, not the unfiltered full list:
   - `PassageReorder.moveQuestion/movePassageBlock` operates on one category's
     list. Fetch that category's id list from the DB when a move control on
     screen (they are the only rows whose reorder math needs more than the
     visible page). At 1–2k rows per category this is cheap and no longer
     requires the full paper list in memory.
   - `categoryBounds` / section-edge buttons: derive from the same
     per-category ordered lists (first/last elements), not from full-list
     indices. Disabled-under-filter contract (the comment at the section
     logic) is preserved exactly.
4. Row content: `questionContent()` keeps its per-row fetch but drops the
   fields lists never show (nothing to change functionally; ensure the DAO
   select does not pull `explanation`+`explanationImage` when the row's
   collapsed state hides it). Add `Image`/text-image caching via the existing
   `QuestionImage` (`BitmapDecoder`) with an LRU memory cache sized ~1 screen.
5. **Passage headers**: today every visible first-member row triggers a
   look-up. Fine under paging; make sure `passagesById` remains a small map
   (only ids for visible groups), not `getPassagesForPaper` for the whole
   paper.
6. **EditorSession**: replace the full `ids` list with a scope descriptor
   (`paperId`, `categoryId?`, `anchorId`) plus a DAO cursor ("id at index ± 1
   in this scope order and id keyset"). Prev/next keeps working from anywhere,
   including from an import-preview list (which stays in memory and keeps
   using the reader/writer lambdas).
7. Bulk ops: already chunked + single-statement (`updateBulkFieldsChunked`,
   `updateCategoryChunked`); no change. Selection UI keeps ids in a Set —
   fine (user-selected, bounded).

**Targets**: opening a 250k-question paper first-frames < 800 ms with a
streamed list; scroll (fast fling) causes no long frames; per-bar filtering
runs in SQL; moving a question inside its category responds < 300 ms.

**Tests**: pure-JVM tests for the PagingSource ordering across chunked
category sets; a CI Robolectric test that inserts ≥ 3k questions (three
chunk-size multiples), page-walks the list forward and back, and asserts
order, filters and reorder semantics match today's behaviour.

### 4.2 Test session (and everything it shares: check, pacing, drill)

**Today's hot paths** (`TestViewModel.kt`, `TestSessionScreen.kt`):

- `refs` state still contains `text` + `explanation` stems for the *whole*
  paper (`TestUiState.refs`). At 250k this is a significant retained string
  set, re-copied on every `_state.update` that touches `refs`.
- `TestSnapshot.persistProgress` writes the whole `questionIds` list (plus
  selections) as JSON into DataStore on every answer toggle/reveal/flag. At
  250k, each write is a multi-MB JSON serialisation + DataStore commit.
- `openAnswerReview` accumulates `reviewRows` for the entire session (only
  rows, in batches — good — but the final list is full-session-sized and a
  giant opened dialog).
- The palette `LazyVerticalGrid(items(refs))` is virtualised and fine, but
  the `flagged`/`bookmarked` sets are O(answered) per lookup in compositional
  lambdas; fine. No change.

**Plan**

1. **Slim refs**: introduce a `SessionRef` (id, passageId, categoryId) for
   ordering; stems for the palette get fetched per palette page. If a stem is
   needed for a palette cell, load on visible demand like the question itself.
   `refs` becomes `List<SessionRef>` (or even an id array + passage map).
2. **Snapshot workload**: move the resume snapshot off DataStore:
   - New `resume_snapshot` Room row (or 4-column table: paperId, index,
     selections JSON *by deltas*, dwell map).
   - `persistProgress` writes **deltas** (currently-changed selections only)
     after each action; on resume it re-reads `questionIds` from the same
     stored sources (PaperLoader + shuffle + drill seed is already stored:
     store the seed, rebuild the session deterministically instead of holding
     250k ids). Snapshot JSON stays a few KB regardless of paper size.
   - Keep "resume by category selection" semantics exactly: the seed +
     selected ids allow deterministic reorder; a changed DB item silently
     falls out — same behaviour as `TestSnapshot.matches(...)` today.
3. Review sheet: already streams. Change only the retention scale:
   - Move to DB-backed review rows: insert rows into the `answer_review` side
     table (or reuse nothing new — actually reuse nothing new: build rows for
     batch, store, delete when sheet closes) and let sheet UI page (paging).
   - Sheet rows beyond the current batch shouldn't exist in memory.
4. Timer + dwell: already isolated state. Keep.
5. **Prefetch like Anki's reviewer**: when question N resolves, background-
   resolve question N+1's content (a read ahead of time — no prefetch of any
   parts other than the next card) in the FSRS/Anki spirit (#5171 class of
   lazy loading but for the "next" object). Cheap, keeps tap-to-cards < 120 ms.
6. Submit is already streamed (`saveAttemptStreamed`). Add:
   - `submit()` progress indicator from batch count (not from the list).
   - Save keeps `selections` map as-is (already just answered questions).

**Targets**: opening a 250k session < 1 s first frame (session order known,
content lazily resolved); navigation p95 < 120 ms with the next-card
prefetch; answering/flagging writes a KB-scale snapshot delta, not MB JSON;
submit of a 250k attempt finishes without OOM (streaming preserved) with
progress shown.

**Tests**: JVM test that a 10k-question session's `persistProgress` JSON
stays bounded by answered count, not pool size; TestSnapshot resume roundtrips
already exist — extend them with `SessionRef` migration + seed-based
reconstruction; review-sheet paging order test (batch boundaries).

### 4.3 Drill (`Get drill` dialog / estudio-only session)

Today the drill already:
- Samples `PassageBlocks.sample(shuffled, count)` off ids only (cheap);
- Reuses the whole test session VM (`drillCount > 0` path).

**Plan**

- Same slim-refs change as Test (§4.2). Nothing else.
- The drill dialog (`num questions` picker) can show `totalQuestions` from
  the library counts: make sure the paper count it shows is from the
  aggregate `counts` map, not from loading questions.

### 4.4 Study

**Today's hot paths** (`StudyViewModel.kt`, `StudyStore.kt`):

- `nextCard → getNextStudyCard` runs `studyInputs(paperId)` — full
  `getRefsByCategoriesChunked` (250k `QuestionRef`s with stems) — **plus**
  `resolveStudyStates` which loads `card_state.getByPaper` (up to 250k rows)
  and allocates a `CardState` domain object per row, **on every single card
  pull**. On a mid phone this is potentially > 500 ms per grade → visible
  studder in the study flow.
- `getStudyCounts` (badge) does the same two loads per paper — and the
  library screen does it **per paper** (N papers ⇒ N× paper cost).
- `resolveStudyStates` re-checks `contentHash` for every row every time.

**Plan**

1. **Session-scoped resolved-state cache** (the Anki card queue analog):
   - On session start, resolve the four pool membership *once* (learning /
     due / fresh / suspension) from a **pair of aggregate queries**:
     `card_state WHERE paperId = :p AND cardState = ... AND dueAt <= now`
     plus a `COUNT(*)` for fresh (questions w/o card_state rows, expressed
     with `LEFT JOIN` on `card_state.paperId = :p`).
   - Keep an in-memory `Map<questionId, CardState>` only for cards actually
     served this session (pull + preview + grade path). Invalidate only the
     served card's row on grade — never re-parse the whole pools.
   - `pullCard` serves from cached pools via `Study.selectNext` with the
     remaining eligible ids; falls back to a single DB pull when the cache is
     cold or invalidated (first pull, external changes, resume).
2. **Hash-reset check applies lazily**: hash-mismatch reset is verified for a
   card **when it is served**, not for the whole paper on every read. The
   stored `contentHash` column comparison is one `WHERE` on the served row.
3. **Counts in SQL** (Library badge): `getStudyCounts` switches to the two
   aggregate queries above; the per-paper loops in `LibraryViewModel` go from
   O(Σ paper sizes) to O(number of papers). Keep eligibility semantics
   identical: `due`, fresh-introduction pacing (`reviewsSinceNew`) only
   affects session depacing — badge counts stay on the same pool definitions
   (the app's guarantee the badge and the session agree).
4. Keep `getStudyQueue` (tricky drill list) building only `leech` pool via the
   same aggregates; its `List<StudyCard>` is bounded by leech count, which
   stays small by definition.
5. Study screen: `pullCard` loop already skips vanished questions; keep.
   `question` load stays per-card only.

**Targets**: per-pull cost after first pull < 150 ms p95 (cache hit);
grading writes one card row + one log row; opening a study session does O(1)
aggregate reads, not per-question reads; a paper with 250k questions but a
210k-learning session still pulls with the same constant cost.

**Tests**: pool-cache invalidation test (grade only invalidates that card);
fresh count query test vs the in-memory implementation (both agree on a
fixture with nested categories); distinct-elapsed test for the depacing
counter.

### 4.5 Search

**Today's hot paths** (`SearchViewModel.kt`, `McqRepository.searchGlobal`,
`QuestionDao.search/searchIncludingOptions`):

- The DAO prefilter returns **full `QuestionEntity` rows for every match**:
  a query like "a" over 250k questions with element JSON + base64 image
  strings materialises the entire match set — the single worst OOM/jank in
  the app at scale.
- `QuestionSearch.filter` then runs full content parsing on every row
  (`toDomainBulk`) off-screen.
- No pagination: `hits` is a full List; SearchScreen renders it all with
  `LazyColumn`.

**Plan**

1. Two-phase search, column-slim:
   - Phase 1 (DAO, column-slim): id + categoryId + passageId + preview stem
     only, `LIMIT` via `PagingSource`; FTS over `searchText` + `options.searchText`
     (add a tiny `options.searchText` column, refreshed at save/import) +
     `passages.searchText`.
   - Phase 2 (in-memory term logic): only for the rows a page is about to
     show, run the existing `QuestionSearch.filter` for quirks the SQL can't
     express (multi-word phrase across tags/element boundaries — the reason
     `prefilterKey` exists). Mismatches are dropped client-side; page sizes
     stay small (e.g. 30). Results ordering preserved (rowid/id order as
     today).
2. `SearchViewModel` exposes `Flow<PagingData<Hit>>` (cached);
   the debounced 300 ms rerun stays (it is already right).
3. Provenance attach (`paperTitle` per hit) becomes a per-page chunked
   category/paper lookup (2 chunked queries), not whole-table reads.
4. Keep scopes QUESTION / QUESTION_AND_OPTIONS semantics by changing the FTS
   **source set**, not the post-filter.

**Targets**: keystroke→results < 350 ms p95 at 250k; any result volume keeps
memory flat (paged); "a" returns the same hits, in the same order, just
streamed.

**Tests**: pure-JVM ordering/set equality between the old full-materialised
search and the new two-phase version on a synthetic bank (use `QuestionSearch`
fixtures); paging boundary test (page size crossing chunk boundaries).

### 4.6 Import

**Today's hot paths** (`ImportViewModel.kt`, `Importer.kt`, `LegacyParser`,
`DataHolder`):

- `ImportUiState.questions` holds the entire picked bank as `QuestionDto`s;
  the preview list and `refreshDuplicates` iterate them in memory (off-main,
  chunked — but all rows in RAM at once). A 48 MB JSON becomes ~OW-size in
  DOM + DTOs held simultaneously.
- `Importer.import` runs one giant transaction for the whole file. Correct
  for atomicity, expensive at 100k rows (lock time, memory of the compiled
  statement set, and it fights readers).
- The whole-file "existing hashes" snapshot uses `questionDao.getAll()` →
  F3 removes this shape (replace with a chunked hash-id stream or
  `SELECT contentHash FROM questions` only).
- Per-question logging (F6).
- `refreshDuplicates` already classifies off-main with a hash-set; fine, keep,
  but base the "interesting" id/hash probe on the FTS-less content-hash index
  (F2) so it is cheap at scale.

**Plan**

1. **Stream the parse**: convert `LegacyParser.parse` to an incremental JSON
   reader (the project already has the pattern in `JsonPull`/`BoundedRead`):
   emit questions one at a time (or per-batch of 500) into
   `MutableSharedFlow`/`Channel`; the ViewModel consumes into a bounded page
   list (e.g. the first 500 for preview) and counts the rest. Preview shows a
   count + a first-N sample + search/sort over the stream; edits to previewed
   questions behave as today (the `originalFile` needs a streamed backing —
   keep a temp-file cache of the parsed stream so `buildImportFile` re-reads
   it, not RAM).
2. **Chunk the write transaction**: import advances in per-paper (or per-batch
   of 2k rows) transactions with a `import_run`-style journal row (`stage`,
   `paperIndex`, counts). On failure crash, "Import continued from where it
   stopped" can resume; on success, one final commit-totals row. Semantics to
   preserve:
   - duplicates/update answers decision logic unchanged;
   - order of papers/categories/questions in the file unchanged;
   - passages same first-member-wins category assignment;
   - backup restore of bookmarks/attempts deduplicated the same way.
   Atomicity shrinks from whole-file to per-chunk — document this in the
   import report (an aborted import leaves only committed chunks; the resume
   row says so).
3. **Memory ceiling**: `existingHashes` becomes a paged/id-scan stream into a
   Bloom-ish `Set` (plain `HashSet` is fine at 250k strings, ~50 MB) — build
   with a `SELECT contentHash` column query in batches. Optionally cap it and
   fall back to per-batch `contentHash IN (...)` lookups (the current behavior)
   if the set is too large.
4. **ImportScreen preview paging**: `LazyColumn` over the preview stays, but
   the `remember` blocks that filter `state.questions` three times (new /
   changed / dup partitions) become three `PagingSource`-style or stateful
   page views over the stream; duplicates badge stays computed per chunk.
5. Docx/Anki importers route through the same stream shape
   (`AnkiPackageReader` already streams from the zip).
6. Keep the process-death paths already fixed (`setNoSourceError`,
   fingerprint dedup).

**Targets**: 100k-question JSON bank imports with peak heap < 60 % and no
main-thread freeze; preview shows first 500 instantly with a running count;
UI stays interactive during the import (main thread only sees paging +
progress events).

**Tests**: JVM fixture tests on the streaming parser (same warnings/rows as
the current tests, driven through the stream); ImportSchedulingTest extended
to mid-file failure + resume behavior; heap-instrumented CI run (prints peak
RSS of the test JVM for a 50k bank) as the perf gate.

### 4.7 Results screen

**Today's hot paths** (`ResultsViewModel.kt`):

- `getAttemptResults(attemptId)` maps **every** `QuestionResultEntity` (full
  element JSON, options JSON, explanation) into `QuestionResult` objects in
  memory at once. Plus `categoryBreakdown` uses the same in-memory list.
- ResultsScreen renders all rows in `LazyColumn` (fine) but the model is
  still O(attempt size) in memory.

**Plan**

1. `question_results` for one attempt becomes a `PagingSource` (rows paged,
   order preserved as `rowid`); `ResultsViewModel` exposes paging + a slim
   aggregate for the header:
   - `categoryBreakdown` → one SQL
     `SELECT categoryTitle, SUM(isCorrect), COUNT(*)... GROUP BY categoryTitle`
     with the "graded" predicate from `correctOptionIds != ''` in SQL.
2. The per-detail sheet (explanation, dwell) reads **that row only** on
   expand (it is already `QuestionResult`-shaped; the paging row carries
   enough to render collapsed state).
3. Keep the "attempt → results screen" route; paging survives process death
   (attemptId in the route).

**Targets**: opening a 250k attempt's results < 800 ms with rows streamed;
memory flat while scrolling; categoryBreakdown computed in one query.

### 4.8 History

**Today's hot paths** (`HistoryViewModel.kt`, `HistoryStore.kt`):

- `getAllQuestionResults()` loads **all** result rows for **all** attempts
  (each row with options JSON) into memory, then aggregates in Kotlin — the
  "hardest questions" and "weakest categories" lists are tiny; the cost is
  the whole scan.
- `Trends.perPaper(attempts)` is O(attempts) in memory (fine — attempts are
  headers).
- `observeAttempts()` streams all attempts (fine — headers, indexed).

**Plan**

1. Add a maintained aggregate table `question_stats`:
   `questionId PK, categoryId, gradedCount, wrongCount, skipCount,
   lastResultIsCorrect, lastFinishedAt` — updated inside the same transaction
   as `insertResults` in `saveAttemptStreamed` (upsert per batch), so it
   never drifts.
2. `HistoryViewModel` reads hardest/weakest from aggregates over that table
   (SQL `ORDER BY wrongCount/gradedCount LIMIT k`), in one indexed query, and
   drops `getAllQuestionResults()` entirely.
3. Weakest categories: same table + a category-join aggregate.
4. Backfill the table in migration v12 from `question_results` in batches
   (one-time cost, off-main, guarded by `INSERT ... SELECT` in SQL if
   possible).
5. Attempt history deletes cascade — deleting an attempt must also **not**
   touch `question_stats` (it's a lifetime aggregate; matches "hardest ever"
   semantics today). Confirm with a migration test.

**Targets**: History first paint is two indexed queries; memory O(results
shown); "hardest/weakest" numbers identical to today's Kotlin aggregation on
a fixture.

### 4.9 Statistics

**Today's hot paths** (`StatsStore.kt`, `StatsViewModel.kt`):

- `StatsStore.load()` is a whole-table read of `review_log` (replayed
  through the FSRS scheduler in memory), `card_state` (all rows → domain), plus
  `getAllIdCategories`, `categoryDao.getAll`, `paperDao.getAll`. At 1M log
  rows / 250k card rows this both OOMs and multiplies CPU.
- Aggregate/slicing happens in `StatsAggregator` over the fully loaded rows.

**Plan**

1. **SQL-first aggregates** with the same outputs:
   - Heatmap/day counts: `SELECT reviewedAt/86400000 AS day, COUNT(*)` from
     `review_log` with the `(paperId, reviewedAt)` index (F2), per scope.
     Range filtering and paper scoping happen in SQL.
   - Workload (future due load): from `card_state` grouped by `dueAt` bucket
     per paper (indexed in F2). Retention/stability histograms read the
     domain columns as aggregates, not per-row materialisation.
   - Response times / "slowest": `review_log.elapsedSeconds` aggregates with
     `ORDER BY elapsedSeconds LIMIT k` per group.
   - Card snapshot list (drill-down candidates) use indexed, LIMITed queries
     with the existing names/orderings.
2. **FSRS replay is capped**: keep `FsrsReplay` for the fitting/optimizer
   (FsrsTuner already loads the whole log — for fitting this is fine at
   hundreds of thousands of reviews; it streams with `onProgress` already).
   For the *stats screen*, replace the replay-based projections with the
   aggregate queries above (the replayed numbers the screen shows must be
   reproducible from `stabilityAfter/difficultyAfter` columns already stored
   per review — derive histograms from columns instead of re-running FSRS).
3. `StatsViewModel` splits `_state.bundle` per section and loads sections
   lazily (screen sections already exist: Overview/Workload/Memory/Retention/
   Cards), each backed by its own query.
4. Keep the drill-down sheet per-card reads (already per-question).

**Targets**: stats screen with 1M log rows paints < 3 s; memory flat; every
graph's numbers exactly match today's on a replay fixture (regression test:
run old pipeline + new pipeline over the same log, assert equal outputs).

### 4.10 Passage screen (editor/passages list)

**Today's hot paths** (`ui/editor/PassagesScreen.kt`, `PassageStore`,
`McqRepository.getPassageMembers`):

- `memberCountsByPassage` already aggregates in SQL (good, keep).
- `getMembersByPassage(passageId)` loads full Question rows for the member
  list (a passage is usually ≤ dozens of members; fine).
- `getPassagesForPaper(paperId)` is used by Browse — replaced there (§4.1).

**Plan**

1. Members list → `PagingSource` (id, stem, sortOrder) same shape as browse.
2. Passage text load stays one row.
3. Delete guard, reorder, member counts keep their SQL aggregates.
4. Passage editor's question-assignment flow already chunked; keep.

**Targets**: a paper with 5k passages lists smoothly; a passage with 2k
members pages.

### 4.11 Bookmarks

**Today's hot paths** (`BookmarksViewModel.kt`, `HistoryStore.getBookmarkedQuestions`):

- Loads **all** bookmarked questions with full content + a category/paper
  lookup, into one List. Bookmarks are user-curated so usually small — but the
  app explicitly supports "bookmark everything".
- Export builds the whole file in memory (F5 covers that); the LazyColumn is
  virtualised.

**Plan**

1. Bookmark list becomes a `PagingSource`:
   `SELECT question.id, stem-fields FROM bookmarks JOIN questions ... ORDER BY bookmarks.createdAt DESC`.
   Content resolves per visible row as today.
2. Remove-all/counts paths already chunked; keep.
3. Export goes through the streaming exporter (F5) with batched content
   loads (300 rows per batch).

**Targets**: 50k bookmarks streams; export does not build whole file in RAM.

---

## 5. Everything else (the screens not listed above, but on the same data)

### 5.1 Library screen + drawer + paper cards

- `LibraryViewModel` currently hits `getStudyCounts(paperId)` per paper
  (full ref + card_state reads). After §4.4's SQL counts, this is one
  aggregate per paper; further, load them in **one** grouped query across
  papers (bonus, not required).
- `LibraryPaperCard` questionCount comes from `observeCategoryCounts`
  aggregate — already cheap.
- `LibraryDialogs` delete-confirmation counts already use aggregate
  queries via `deleteImpact`; keep.
- Drawer category reorder mutates `sortOrder` — keep; it is O(siblings).

### 5.2 Settings / Storage report

- `McqRepository.storageReport` loads `questionDao.getAll()` **and** every
  `OptionEntity`, then sizes the DB file in Kotlin. Replace with SQL:
  `SELECT categoryId, SUM(LENGTH(text)) ...`, `SELECT questionId, SUM(LENGTH(text)) ... GROUP BY`
  and one `COUNT` per paper from the aggregates (no materialisation).
- FSRS tuner `optimize` already loads the whole review log once with
  progress callbacks — acceptable for "hundreds of thousands"; add the same
  memory hint as `StatsStore` (stream batches into the fitter rather than
  one list) if profiling says so.

### 5.3 Question editor

- Uses `EditorSession` scope-fix from §4.1 step 6.
- `saveQuestion` is already transactional with per-row work — fine; add the
  `searchText` maintenance (F4) inside the same transaction.
- Import-preview editing (`importReader/importWriter`) keeps working; its
  queue is the bounded preview page (§4.6), not the whole file.

### 5.4 Backup / restore

- `Backup.kt` currently builds the whole backup payload in memory. Apply F5
  (streamed writes) — the file is already zipped; write members as you go.
- Restore is `Importer` (§4.6 stream + chunked transactions).
- Keep the backup format bytes-identical for Anki/tests (round-trip tests
  already cover this).

### 5.5 Anki interop (apkg import/export)

- `AnkiPackageReader` already streams from the zip into temp sqlite. Keep.
- `AnkiPackageWriter` gets the streaming write (F5); schema-11 export must
  stay legacy-compatible (three fields, filename-keyed media) — the
  `AnkiRoundTripTest` fixtures are the gate.
- Export of a 100k deck builds notes in batches of 500 and inserts into the
  export temp DB progressively rather than all at once.

### 5.6 Paper exporters (PDF/DOCX/HTML/ZIP)

- `PaperExporter`/`PdfPaperWriter`/`DocxPaperWriter` should take a stream and
  process question batches as today's `LegacyParserLargeBankTest`-scale
  fixtures do; same output bytes as now (verified by `ExportWritersTest`).
- PDF with base64 images already downscales at import; ensure decode uses
  `BitmapDecoder` with `inSampleSize` bound at export too.

### 5.7 Cross-cutting UI hygiene for huge lists

- Every `LazyColumn`/`LazyVerticalGrid` gets `key` (already mostly true) and
  `contentType` where heterogeneous rows exist (headers vs cards in Browse,
  palette cells vs. review rows) so recycling stays tight.
- No UI-lambda construction that allocates per cell per recomposition
  (sort/`group by` in `remember` keyed on the exact inputs — the pattern the
  codebase already settled on).
- State updates that can grow (selections, flagged, dwellSeconds maps) keep
  growing boundedly (only touched questions) — fine. Do not add per-second
  `state.update` copies of big lists (the dwell split already prevents
  that).

### 5.8 Start-up

- `McqApplication`/`MainActivity` do not need the DB at compose time.
  Verify nothing initialises the repository eagerly on the main thread; if
  it does, defer to first navigation (measure with a startup trace on CI).

---

## 6. Implementation order

1. **P0 — branch correctness**: finish the ref migration in Browse (F1),
   CI compile pass, no feature change.
2. **Foundations**: F2 (indexes) + F3 (WAL, schema export, remove whole-table
   DAO queries) + F6 (log gating). Small, high leverage, no feature change.
3. **Biggest jank wins, in order of user pain:**
   1. Study session/thread + badge counts (§4.4, per-pull cost → constant).
   2. Search two-phase + FTS (§4.5 + F4 backfill in the same migration).
   3. Browse → Paging + SQL filters + per-page reorder context (§4.1).
   4. Test snapshot deltas + slim refs + review-sheet paging (§4.2).
4. **Aggregate tables**: `question_stats` (§4.8) + SQL-first statistics
   (§4.9). Both touch stored data → migration + backfill.
5. **Results + Bookmarks page their rows** (§4.7, §4.11).
6. **Import streaming + chunked transactions + preview paging** (§4.6) +
   export streaming (F5) + Anki writer streaming (§5.5).
7. **Hygiene batch**: StorageInfo SQL (§5.2), `contentType`s, startup trace,
   editor session cursor (§5.3).

Each numbered step is independently shippable; nothing later depends on a
feature change earlier, only on the foundations.

---

## 7. Verification strategy

- **Synthetic fixtures**: a JVM bank generator (tests only) producing
  parametric banks (1k / 50k / 250k / 500k questions with realistic stems,
  options, passages, tags). Reused by: search parity, paging order, stats
  aggregates, import streaming, snapshot budget.
- **Pure-JVM perf guards** (run in CI's x86_64): assertions like
  "aggregate due count over 250k card rows < X ms", "FTS search parity with
  the legacy walk", "snapshot JSON size ≤ f(answered)" — cheap, deterministic,
  no Robolectric.
- **Robolectric behavior tests** (CI only, per AGENTS.md): list paging order
  vs. legacy order, stats regressions, chunk-boundary paging.
- **Manual matrix on device** (ARM64 host: install the `app-debug` CI
  artifact and drive the app): open 250k paper / scroll fling / search /
  test 500-question drill / study pull / import 100k bank / submit / stats.
  Watch `adb logcat` for `Choreographer` skips and `Logger` timings (these
  are already in place for Browse/Test first-loads).
- **Heap gate**: a CI log capture from the import fixture run (heap
  before/after) as a regression signal (pattern already used in
  `BrowseViewModel` and `Importer` heap logging).

Feature invariance: every column of behaviour today (ordering rules, filter
semantics, FSRS scheduling, backup/Anki format) keeps its existing tests —
the plan's changes are only about *how much* is read at once; each section
names the test that already pins the behaviour that must not change.
