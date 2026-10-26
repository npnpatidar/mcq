# Consolidated bug registry

Single-file consolidation of every bug report that lived in this repository. The nineteen source
files (`bugs.md`, `bugs_all.md`, `new_bugs.md`, `third_bugs.md` … `fourteenth_bugs.md`,
`eighth_bugs.md`, `search_bugs.md`, `settings_bugs.md`, `stats_bugs.md`, `passages_bugs.md`,
`ui_bugs.md`, `browse_bug.md`, `drill.md`, `add_bugs.md`) have been removed; this file is now the
only bug registry. `category_study.md` and `category_card_counts.md` are feature/implementation
plans, not bug reports, and were left in place.

**Verification baseline:** every claim below was re-read against the working tree at
`9d1a179` (branch `feature/host-build-notes`, 2026-10-25) — the commit that added the reports.
Reports marked "report only, nothing fixed yet" were confirmed finding-by-finding to still
reproduce in the code. The older six reports (already consolidated once in the deleted
`bugs_all.md`) were re-verified row-by-row: all 82 "Fixed" claims hold (three evidence pointers
had drifted and are corrected here), and both "N/A" rows are correct.

**Method / limits:** verification is by reading the current source. This host is Linux/aarch64
and cannot run Robolectric-backed (SQLite) tests, so database-path claims are CI-authoritative.
No production code was changed by this consolidation.

---

## How to read this file

- **Status** — `OPEN` = verified present in the current code. `FIXED` = the fix was verified in
  the tree (kept as history so the same bug is not re-chased). `BY DESIGN` = deliberate
  behaviour or documented trade-off; do not "fix" without an owner decision. `NOT A BUG` =
  premise disproved by the code.
- **Kind** — `BUG` = incorrect behaviour, data loss, crash or a violated contract.
  `NIT` = cosmetic, doc, performance, or latent-only. `CLEANUP` = dead code.
- **Severity** is the source report's, re-graded only where verification corrected the premise.
- **Reachability** notes (`Likely`, `latent`, `needs rotation`, …) are carried from the reports
  and, where relevant, re-confirmed by the verifier.
- **Source** — the original report and finding number, kept for provenance only (those files no
  longer exist). IDs (`A1`, `F5`, …) are the stable references to use from now on.

**Totals:** 110 open findings (77 BUG, 33 NIT/CLEANUP) · 84 historical findings (82 FIXED,
2 NOT A BUG/BY DESIGN) · 45 recorded by-design/not-a-bug items · 15 needing device/CI/owner
adjudication.

---

## Part 1 — Open findings (verified present)

### Index

| ID | Sev | Kind | Area | Finding |
|---|---|---|---|---|
| A1 | High | BUG | Parser | `JsonPull.scan()` recurses per separator → `StackOverflowError` kills the app — **FIXED** |
| A2 | High | BUG | Import | Question id owned by another paper is REPLACE-moved into the importing paper — **FIXED** |
| A3 | High | BUG | Import | Reused passage id drags another paper's member questions across — **FIXED** |
| A4 | Medium | BUG | Import preview | Same content under a different id counts "new" in preview, "duplicate" in import — **FIXED** |
| A5 | Low | BUG | Parser | Root `questions` before `papers` adds a phantom paper in the streaming reader — **FIXED** |
| A6 | Nit | NIT | Anki export | Paper title containing `::` seeds an extra empty deck — **FIXED** |
| B1 | Medium | BUG | Search | Global-search passage post-filter uses `"\n"`; stored column uses `" "` |
| B2 | Medium | NIT | Search | `Scope.OPTIONS` over-fetches (walks text/tag candidates the authority rejects) |
| B3 | Low | BUG | Search | A scope tap commits the stale debounced query; debounce timer not restarted |
| B4 | Low | BUG | Browse | Search input can revert a keystroke in the commit frame |
| B5 | Nit | NIT | Search | `escapeLike` KDoc is orphaned above `prefilterKey` |
| B6 | Nit | NIT | Search | Unused `repository` parameter; dead trailing `Spacer` |
| C1 | Medium | BUG | Passages | Bulk "move to category" strands passage members (no enforcement/re-placement) |
| C2 | Medium | BUG | Passages | Bulk "copy to category" keeps `passageId` with no enforcement/placement |
| C3 | Medium | BUG | Passages | Reorder arrows write `sortOrder`, but the list query has no `ORDER BY` |
| C4 | Medium | BUG | Passages | Editing a passage (even untouched) deletes its image |
| C5 | Low | BUG | Browse | Passage header first-member lookup uses a different order than the list |
| C6 | Low | BUG | Session | Mistakes-only order can split a passage set across the drill/shuffle |
| C7 | Low | BUG | Editor | Passage picker hidden when the category has none → stray member unassignable |
| C8 | Nit | NIT | Passages | Edge reorder taps silent; flat/mixed list; new passages all `sortOrder=0` |
| C9 | Nit | NIT | Passages | Dead `updateFields`/`insertIgnore`; wrong KDoc; needless `suspend` |
| D1 | High | BUG | Resume | Drill and non-drill sessions are interchangeable on resume — **FIXED** |
| D2 | Low | NIT | Drill | `Drill.sample` splits passages, has no production caller (trap) — **FIXED** |
| D3 | Nit | NIT | Drill | `drillRequested` KDoc references a nonexistent `[questions]` field — **FIXED** |
| D4 | High | BUG | Resume | A mistakes-only round and a full test are interchangeable on resume — **FIXED** |
| D5 | Medium | BUG | Resume | Resumed selections/flags are not filtered to the rebuilt order ("5 of 3") — **FIXED** |
| D6 | Low-Med | BUG | Timer | No snapshot write on backgrounding → kill rolls the countdown back — **FIXED** |
| D7 | Low-Med | BUG | Timing | Untimed attempts lose pre-kill elapsed time on resume — **FIXED** |
| D8 | Low | BUG | Submit | A failed snapshot clear leaves a resumable already-saved attempt — **FIXED** |
| D9 | Low | BUG | Resume | `prefetched` survives `resume()` → first card can show the old option order — **FIXED** |
| D10 | Low | BUG | Timing | Dwell/untimed duration use wall clock; countdown uses monotonic clock — **FIXED** |
| D11 | Low | BUG | Scoring | Imported `negativeMarking` is unvalidated → negative makes wrong answers gain marks — **FIXED** |
| D12 | Nit | NIT | Routes | A category id containing `,` cannot round-trip through `test?categories=` — **FIXED** |
| E1 | Medium | BUG | Stats | Every Stats entry replays the whole log twice; first result discarded |
| E2 | Medium | BUG | Stats | Superseded builds are never cancelled → concurrent full-log replays |
| E3 | Low | BUG | Charts | StackedBarChart overlay bridges null gaps the LineChart breaks |
| E4 | Low | BUG | Charts | `niceMax` violates its contract for values in (0, 1) |
| E5 | Low | NIT | Charts | Heatmap rows claim Monday..Sunday but are `index % 7` from an arbitrary weekday |
| E6 | Low | BUG | Stats | Three "overdue" boundaries on one page (backlog/forecast/overdueAge) |
| E7 | Low | NIT | Workload | Time-per-day tooltip prints raw seconds under an "1h" axis |
| E8 | Low | BUG | Stats | A failed stats load has no retry |
| E9 | Low | BUG | Stats | Per-paper table is one LazyColumn item (no recycling) |
| E10 | Nit | NIT | Stats | Deleted papers fall back to raw ids in labels |
| E11 | Nit | NIT | Replay | Junk ratings silently fold into GOOD/EASY |
| E12 | Nit | NIT | Stats | One fixed zone offset buckets all history (DST/travel) |
| E13 | Nit | NIT | Sheet | Predicted retention truncates instead of rounding |
| E14 | Nit | NIT | Stats | Scope selector's paper list read once in `init` |
| E15 | Nit | NIT | Stats | KPI pairs retention (subset) with reviews (all) without a qualifier |
| F1 | High | BUG | Import UI | Process-death guard fires on every recreation → destroys a live import | — **FIXED**
| F2 | High | BUG | Library | Stale export scope falls through to `exportAll` (whole library) | — **FIXED**
| F3 | High | BUG | Test | Countdown never restarts after a failed save (auto-submit dead) | — **FIXED**
| F4 | Medium | BUG | Bookmarks/Settings | Picked file silently ignored after activity recreation | — **FIXED**
| F5 | Medium | BUG | Browse | Deep-link auto-scroll re-fires on every filter/pager generation | — **FIXED**
| F6 | Medium | BUG | Results/Search/Bookmarks | `LoadState.Error` renders as a blank list; append errors unhandled | — **FIXED**
| F7 | Medium | BUG | Browse | Selection survives filter changes; bulk ops hit hidden rows | — **FIXED**
| F8 | Medium | BUG | Test | Question scroll position carries over to the next question | — **FIXED**
| F9 | Low | BUG | Browse | `openEditor` clamps an out-of-scope anchor to a wrong cursor | — **FIXED**
| F10 | Low | BUG | Browse | Selection state is plain `remember` → rotation discards it | — **FIXED**
| F11 | Low | NIT | Browse | Selection export ordered by tick order, not list order | — **FIXED**
| F12 | Low | NIT | Library | Every library dialog lives in plain `remember` | — **FIXED**
| F13 | Low | BUG | Library | Picked file read in the composition scope; recreation drops it | — **FIXED**
| F14 | Low | BUG | Bookmarks | Every bookmark toggle rebuilds the pager → deep scroll jumps to top | — **FIXED**
| F15 | Low | BUG | Bookmarks | "Delete" calls `toggleBookmark`; a double-tap re-adds the row | — **FIXED**
| F16 | Low | BUG | Settings | `AnkiNumberRow` display and stored value diverge (1.5 → "1", slow/fast clamp) | — **FIXED**
| F17 | Low | NIT | Settings | Retention slider writes DataStore on every drag frame | — **FIXED**
| F18 | Low | BUG | Bookmarks | Export opens the output stream on the main thread | — **FIXED**
| F19 | Nit | NIT | Test | "No questions in this selection" flashes at session start | — **FIXED**
| G1 | High | BUG | Tables | Table cell markup renders as literal tags in-app/HTML |
| G2 | High | BUG | Tables | A picture in a DOCX cell renders as a base64 blob |
| G3 | High | BUG | Tables | Equations in cells render as raw MathML |
| G4 | Medium | BUG | Import | DOCX merged cells (`w:gridSpan`) shift later cells a column left |
| G5 | Medium | BUG | PDF | Mis-nested inline closes dropped, not popped → formatting lost/leaked |
| G6 | Medium | BUG | DOCX | `tableXml` writes cells as escaped plain text (formatting lost) |
| G7 | Low | BUG | Mixed view | `stripMathAttributes` keeps only the first `<math>`; surrounding text dropped |
| G8 | Low | BUG | In-app vs exports | Unknown tags: screen strips, exporters keep; the KDoc says the opposite |
| G9 | Low | BUG | Mixed view | `$…$` TeX delimiters mangle ordinary text containing two `$` |
| G10 | Nit | NIT | Tables | Cell newlines render differently in native vs mixed view/HTML |
| G11 | Nit | NIT | Exports | Consecutive `<br>`s collapse on screen but not in DOCX |
| G12 | Nit | NIT | PDF | Table columns measured on entity-encoded text |
| G13 | Nit | NIT | Editor | Ragged-table add-row sizing; silent cell no-op |
| H1 | Medium | BUG | Settings | Backup/restore failures report under "Export failed" |
| H2 | Medium | BUG | Settings | A silently rejected typed value stays in the field; the row lies |
| H3 | Low | BUG | Settings | `fastSeconds` UI range (1..600) disagrees with the domain clamp (1..3600) | — **FIXED**
| H4 | Low | BUG | Settings | Whole-config writes can revert freshly fitted FSRS weights |
| H5 | Low | NIT | Settings | Backup manifest lists every attempt row just to count them |
| H6 | Low | BUG | Settings | No busy state/progress; leaving the screen silently cancels a restore |
| H7 | Nit | NIT | Settings | Hardcoded labels; descriptions spliced mid-sentence across resources |
| H8 | Nit | NIT | Settings | Fractional relearn minutes render truncated |
| H9 | Nit | NIT | Settings | "Export Logs" can silently do nothing; `startActivity` unwrapped |
| H10 | Nit | NIT | Settings | Transient dialog state is plain `remember` (rotation loses it) | — **FIXED**
| I1 | Medium | BUG | Optimizer | Outlier buckets and S0 pretrain use a wider dataset than the reference |
| I2 | Low-Med | BUG | Replay | `FsrsReplay.replay` maps keyed globally, contradicting its id contract |
| I3 | Low-Med | BUG | Study | A pull failure after a persisted grade misreports and invites double-persist |
| I4 | Low | BUG | Study | `stop()` during an in-flight grade is undone |
| I5 | Low | BUG | Study | `pullCard` skip-loop is unbounded |
| I6 | Nit | NIT | Study | `retry()` has no in-flight guard (double-tap double-loads) |
| J1 | High | BUG | Editor | Delete after Prev/Next deletes the previously shown question | — **FIXED**
| J2 | High | BUG | Editor | Delete is live in the import-preview editor and deletes a library row | — **FIXED**
| J3 | Medium | BUG | Editor | Formula edits can be lost (400 ms debounce vs save/move) | — **FIXED**
| J4 | Medium | BUG | Editor | Stale formula shown after Prev/Next (block key not per-load) | — **FIXED**
| J5 | Medium | BUG | Editor | Init failure strands the editor on "Loading…" forever | — **FIXED**
| J6 | Medium | BUG | Editor | Process death during import editing → phantom edit; save crashes (FK) | — **FIXED**
| J7 | Low-Med | BUG | Editor | Unguarded save/delete; double-tap Save duplicates a new question | — **FIXED**
| J8 | Low | BUG | Editor | Legacy image-only option makes a question permanently unsavable | — **FIXED**
| J9 | Low | BUG | Editor | A question with no correct answer saves silently (import warns) | — **FIXED**
| K1 | Low-Med | BUG | Browse | A slow refresh blanks the whole screen (controls unmounted) |
| K2 | Low | BUG | Browse | Punctuation-only tags field clears all tags on bulk edit |
| K3 | Low | NIT | Browse | Transient stale-rowMoves race after a write |
| K4 | Low | CLEANUP | Browse | Dead filter overloads / `swapQuestions` / `indexInCategory` |
| K5 | Nit | NIT | Browse | Hardcoded English strings in browse dialogs |

---

### A. Import, parser, Anki export — from `eighth_bugs.md`

**A1 · High · BUG — `JsonPull.scan()` recurses once per separator → app crash.** *FIXED: `scan()` loops over separators (`JsonPull.kt`); pinned by the enabled `JsonPullSeparatorRecursionTest`.*
`JsonPull.kt:213-216`: the `,`/`:` branch calls `scan()` for each consecutive separator. A run of
~100k commas (legal JSON, trivially crafted) overflows the stack; `StackOverflowError` is an
`Error`, so every `catch (Exception)` boundary (parser, streaming importer, import-screen
handlers) misses it and the process dies instead of reporting "Could not parse the file".
Committed `JsonPullSeparatorRecursionTest` pins the fix; it is committed as
`@Ignore("documents consolidated_bugs.md A1")` so CI stays green, and is enabled when the fix
lands (CI-authoritative). *Fix:* loop over separators; optionally catch `Throwable` (rethrow
`CancellationException`) at parse boundaries.

**A2 · High · BUG — a question id owned by another paper is moved, not copied.** *FIXED: ids owned by another paper are remapped (`paperId-id`) in both `Importer.kt` and `StreamingImportSession.kt`, mirroring the category path; pinned by the enabled `ImportPaperIdentityTest.aQuestionIdOwnedByAnotherPaperIsNotMoved`.*
`Importer.kt:406-431`: `getById` is global (no paper check) and the upsert is
`@Insert(REPLACE)` (`Daos.kt:1243-1244`), so an incoming question whose id already exists under
another paper is deleted and re-inserted under the importing category; its options/answer key
are then replaced. The category path has an explicit cross-paper guard (`Importer.kt:242-253`);
questions have none. `StreamingImportSession.kt:459-484` mirrors the hole. Hand-authored banks
number from `1`, so collisions are easy. Pinned by committed (CI)
`ImportPaperIdentityTest.aQuestionIdOwnedByAnotherPaperIsNotMoved`, `@Ignore`d until fixed.
*Fix:* remap (e.g. `"$paperId-$id"`) or skip+warn when the id belongs to a different paper; same
in the streaming session.

**A3 · High · BUG — a reused passage id drags another paper's questions along.** *FIXED: passage ids owned by another paper are remapped in both importers, members follow the remap, and the stray-fold sees only this import's effective ids; pinned by the enabled `ImportPaperIdentityTest.aPassageIdOwnedByAnotherPaperDoesNotDragMembers`.*
`Importer.kt:298-305` upserts the passage by id with REPLACE (same move problem), then
`:485-497` folds every member whose category differs into the importing category — including the
owning paper's members. One colliding passage id migrates a whole passage group.
`StreamingImportSession.kt:581-589,592-612` mirrors it. Pinned by CI
`ImportPaperIdentityTest.aPassageIdOwnedByAnotherPaperDoesNotDragMembers`, `@Ignore`d until fixed.
*Fix:* ownership check on the passage row and restrict the fold to ids this import wrote.

**A4 · Medium · BUG — preview says "new", the importers skip it as duplicate.** *FIXED: `refreshDuplicates` keeps the hash-matched rows and `classifyPreview` takes `storedIdsByHash`, classifying same-content-different-id as duplicate; pinned by the enabled `ImportPreviewClassificationTest`.*
`ImportViewModel.kt:312-332` classifies against `storedById[q.id]` only, while
`refreshDuplicates` (`:457-467`) fetched rows for ids ∪ hashes. Same content under a different
id therefore classifies as new, but both importers deduplicate by content across the library
(`Importer.kt:583-597`; streaming twin `:449-457`) and skip it. Pinned by committed
`ImportPreviewClassificationTest`, `@Ignore`d until fixed. *Fix:* classify against hash-matched
stored rows too.

**A5 · Low · BUG — root `questions` before `papers` adds a phantom paper (streaming only).** *FIXED: `walkRootObjectStreaming` buffers root-question batches and decides at the end of the object, as the whole-file reader does; pinned by the enabled `LegacyParserTest.rootQuestionsBeforePapersAddsNoPaper`.*
`LegacyParser.kt:341-343` decides "as paper" when the key is read; the whole-file reader
(`:715-717`) decides after the object ends. JSON objects are unordered, so the same file yields
two papers (preview/large import) or one (small import). Pinned by committed
`LegacyParserTest.rootQuestionsBeforePapersAddsNoPaper`, `@Ignore`d until fixed. *Fix:* remember root
questions and decide after the object ends, as the whole-file path does.

**A6 · Nit · NIT — a paper title containing `::` exports an extra empty deck.** *FIXED: `AnkiPackageWriter` seeds `deckIds` with `segmentFor(paperTitle, "Untitled")`.*
`AnkiPackageWriter.kt:133` seeds `deckIds[paperTitle.trim()]` (raw) while notes use
`deckNameFor`/`segmentFor` (`::` → `: `, `:342`). A title `A::B` registers both `A::B` (unused)
and `A: B`; Anki imports the phantom nested deck. Blank title likewise (`""` vs `Untitled`).
*Fix:* seed with `segmentFor(paperTitle, "Untitled")`.

---

### B. Search — from `search_bugs.md`

**B1 · Medium · BUG — the passage post-filter string disagrees with the stored column.**
`McqRepository.kt:277` builds `passage.title + "\n" + searchableText` (no trim), while every
writer stores `passageSearchText` = `title + " " + body` trimmed (`Models.kt:158-159`,
`QuestionContentMapper.kt:102-103`). The SQL prefilter matches the stored column, then the
in-memory authority (`QuestionSearch.kt:78-80`) vetoes a phrase straddling the title→body
boundary. Browse's SQL-only query finds it — two search surfaces disagree on identical data.
*Fix:* build the map with `passageSearchText(...)`; add a CI assertion to
`PassageRepositoryTest.globalSearchFindsAMemberThroughItsPassageText` for `"cycle rain"`.

**B2 · Medium · NIT (performance) — `Scope.OPTIONS` walks every candidate to exhaustion.**
`McqRepository.kt:267-268` routes OPTIONS to `searchIncludingOptions` (union of text, tags,
options, passage), while the authority accepts only option matches
(`QuestionSearch.kt:83`). A common word matching many stems but no options parses the whole
candidate set with all options to end at "no matches". Correctness is fine; cost lands on the
Options chip. *Fix:* an options-only prefilter for that scope.

**B3 · Low · BUG — a scope tap commits the stale debounced query.**
`SearchViewModel.kt:51-54`: `combine(query.debounce(300), scope)` — a scope change re-emits
immediately with the last *debounced* query, so the pager paints stale results for 300 ms and
the pending debounce is not restarted. (Verification correction: the report's secondary claim
that "Searching…" stays hidden is wrong — `debouncing` compares the live trimmed query and is
true mid-typing.) *Fix:* debounce the combined request and/or compare the whole
`SearchRequest`.

**B4 · Low · BUG — the browse search field can revert a keystroke.**
`BrowseScreen.kt:235-237`: the sync-back effect copies `filterState.query` into `searchInput`
whenever they differ; a keystroke in the frame after the debounced commit lands is overwritten.
One-frame window. *Fix:* sync back only when unfocused, or key on request generation.

**B5 · Nit · NIT — `escapeLike`'s KDoc is orphaned.**
`QuestionSearch.kt:15-20` documents `escapeLike` but floats above `prefilterKey`'s own KDoc;
`escapeLike` (`:52-57`) is undocumented. Move the doc down.

**B6 · Nit · NIT — dead surface on `SearchScreen`.**
`repository: McqRepository` is an unused parameter (`SearchScreen.kt:48`); the trailing
`Spacer(Modifier.width(8.dp))` (`:154`) is a leftover (verification: it acts as accidental 8 dp
end padding rather than a literal no-op). Remove both.

---

### C. Passages — from `passages_bugs.md`

**C1 · Medium · BUG — bulk "move to category" strands passage members.**
`QuestionStore.kt:405-434` writes `categoryId` only — no `enforcePassageCategory`, no
`placeWithGroup` — unlike `savePassage`, `saveQuestion`, `assignQuestions` and the importer.
A moved member's passage row stays in the source category, so the editor/picker no longer offers
it (shows "No passage"), the group splits (possibly across papers, wiping card state/review log
on a cross-paper move), and `deletePassage` stays refused. `saveQuestion`'s enforcement is gated
on the assignment *changing* (`:287`), so a later save does not heal it. *Fix:* redirect members
to the passage's category or move the passage row with them and re-place the group.

**C2 · Medium · BUG — bulk "copy to category" creates out-of-place members.**
`QuestionStore.kt:437-455` clones keep `passageId` (`:318`), with no enforcement/placement and
append at the target tail (`:309-314`). Partial selection → two runs; same-category copy → a
second run outside the group; full cross-category copy → passage-ownership break as C1. Member
count grows so the passage cannot be deleted. *Fix:* drop `passageId` on the clone when the
target differs from the passage's category, or route through `assignQuestions` semantics.

**C3 · Medium · BUG — reorder arrows write a field the list never reads.**
`PassagesScreen.kt:112` observes `passageDao.observeAll()`; `observeAll`/`getAll`
(`Daos.kt:173-177`) have no `ORDER BY`, while `movePassage` renumbers `sortOrder`
(`PassageStore.kt:173-188`). The arrows therefore have no visible effect in the management list.
Additionally the REPLACE upsert (`Daos.kt:206-207`) re-inserts the row with a fresh rowid, so
editing any passage moves it to the end of the rowid-ordered list. *Fix:* `ORDER BY sortOrder,
rowid` in `observeAll`/`getAll` (or sort on the screen).

**C4 · Medium · BUG — editing a passage silently deletes its image.**
`PassagesScreen.kt:490-497` builds the saved `Passage` from id/category/title/elements only;
`image` defaults to null (`Models.kt:282`) and `savePassage` writes it as-is
(`PassageStore.kt:61-64`). Every save — including an untouched open+Save — wipes the picture,
which both importers and all three renderers support. *Fix:* carry `image = initial?.image`.

**C5 · Low · BUG — the passage header's first-member lookup uses a different order.**
`Daos.kt:736-740` orders by `sortOrder, rowid` only; Browse (and every other member query) uses
`categories.sortOrder, categories.title, questions.sortOrder, questions.rowid`
(`Daos.kt:674-675`). The KDoc's "members share one category" assumption is exactly what C1/C2
break, so a split group draws its header on a later row. *Fix:* join `categories` and match the
display order; drop the assumption from the KDoc.

**C6 · Low · BUG (data-shape) — mistakes-only order can split a passage set.**
`Daos.kt:1952-1960` orders mistakes most-recent-first; `SessionOrder.of` builds blocks from runs
of equal `passageId` (`PassageBlocks.kt:31-42`) and `shuffled`/`sampled` permute runs
(`SessionOrder.kt:76-89,131-146`). Two members missed at different times become two blocks and
can be placed far apart, contradicting the "a drill never splits a passage" rule. The passage is
still rendered, so severity stays low. *Fix:* group members when building the mistakes order, or
document the exception at the D1 comment.

**C7 · Low · BUG — a stray member cannot be unassigned.**
`QuestionEditorScreen.kt:205-208` composes the passage dropdown only when the current category
has passages; "Remove from passage" lives inside that dropdown (`:512-519`). A member pointing at
another category's passage shows "No passage" and, when the current category has no passages, has
no unassign action at all (changing category drops it as a side effect). *Fix:* show the picker
whenever `state.passageId != null`, or add a standalone remove action.

**C8 · Nit · NIT — reorder/creation rough edges.**
Arrows never disabled at a category edge (`movePassage` clamps and returns false, silent); the
list is flat and paper-mixed while `movePassage` reorders within one category; new passages all
get `sortOrder = 0`; changing a passage's category keeps the old `sortOrder` (collides with the
target's positions).

**C9 · Nit · NIT — dead and misleading passage surface.**
`PassageDao.updateFields` (also skips `searchText`/`searchTextFolded` — a future trap) and
`insertIgnore` have no callers; `PassageStore.buildBlocks`' KDoc names callers that do not exist
(only tests use it); `PassageEntity.toPassageDto()` is `suspend` for no reason.

---

### D. Drill and test-session resume — from `drill.md` and `tenth_bugs.md`

**D1 · High · BUG — drill and non-drill sessions are interchangeable on resume.** *FIXED (with D4): `TestSnapshot` carries `isDrill`/`mistakesOnly`/`drillRequested`; `matches()` requires the same shape and `resume()` restores it, clearing the stray drill seed on a non-drill snapshot.*
`TestSnapshot.matches()` (`TestSnapshot.kt:47-48`) compares only paper + categories.
`resume()` (`TestViewModel.kt:828-841`) adopts the saved order but keeps the new session's
`isDrill`/`drillRequested`, and `saved.drillSeed?.let { ... }` (`:819`) has no else, so a fresh
drill's seed survives a non-drill snapshot. `persistProgress` then writes a header pairing a seed
with a count never applied. Consequences (all reachable, no race): a 500-question full test
resumed from a drill collapses to 10 on the next resume; a drill resumed as a full test silently
drops the cut; a 10-drill resumed as 20 redraws a different cut and loses answers outside it.
Also `ungradedTotal` was computed for the old order. *Fix:* restore the drill fields from the
snapshot (or add them to `matches()`), and treat D4 (mistakes-only) in the same change.

**D2 · Low · NIT — `Drill.sample` is a trap.** *FIXED: `Drill.sample`/`canSample` deleted (the live cut is `SessionOrder.sampled`); tests rebased on `checkCount`.*
`Drill.kt:40-43` shuffles and cuts per question, splitting passage blocks, contrary to the
block rule. No production caller (live cut is `SessionOrder.sampled`), and
`DrillCountAvailabilityTest:91` pins the splitting. *Fix:* delete `sample`/`canSample` or delegate
to the block rule.

**D3 · Nit · NIT — stale KDoc.** *FIXED: the KDoc now references `[order]`.* `TestViewModel.kt:80` references a nonexistent `[questions]`
field (now `order`).

**D4 · High · BUG — mistakes-only rounds and full tests are interchangeable on resume.** *FIXED (with D1): `mistakesOnly` persisted and enforced in `matches()`, restored in `resume()`.*
`TestSnapshot` has no `mistakesOnly` field; a mistakes round is started with no categories, so
its snapshot matches every whole-paper test (and every other mistakes round) of that paper.
`checkResumeOffer` offers it and `resume()` keeps the *new* session's `mistakesOnly`. A full test
(200) resumed as Mistakes becomes a permutation of today's mistakes with the old answers, and
`saveAttemptStreamed` iterates only the order — the other answers are dropped. The reverse turns
a 5-question round into a 200-question test. (This is the mistakes-only half of the same
conflation as D1; one fix must cover both.) *Fix:* persist `mistakesOnly` (+ drill fields) and
include them in `matches()`, restoring the shape in `resume()`.

**D5 · Medium · BUG — a resume does not filter saved answers to the rebuilt order.** *FIXED: `resume()` intersects selections/revealed/flagged/dwell with the rebuilt order's ids.*
`resume()` adopts `selections`/`revealed`/`flagged` wholesale (`TestViewModel.kt:831-837`);
`answeredCount` counts all selections (`:96`) while `questionCount` is the order size (`:98`).
The header/dialog then print "5/3 answered" and `SubmitSummary.unanswered` goes negative (line
silently dropped). *Fix:* intersect the maps with the rebuilt order (or count only order ids).

**D6 · Low-Med · BUG — no snapshot write when the app backgrounds.** *FIXED: `onAppBackgrounded()` persists the snapshot after banking the dwell.*
`persistProgress` runs only on answer/reveal/flag/goTo; `onAppBackgrounded`
(`TestViewModel.kt:477-479`) only pauses dwell, `flushDwell` (`:436-447`) does not persist. A
kill/swipe after answering restores the older `remainingSeconds` (and loses unbanked dwell).
*Fix:* persist after the flush in `onAppBackgrounded()`.

**D7 · Low-Med · BUG — untimed attempts lose elapsed time on resume.** *FIXED: the snapshot banks `elapsedSeconds`; `resume()` re-anchors the start on it.*
`submit()` stores `now - startTimestamp` (`:703-707`); `resume()` resets `startTimestamp` (`:843`)
and the snapshot has no elapsed field. A 40-minute untimed session killed and resumed for 5
minutes is saved as 5 minutes. *Fix:* persist the untimed elapsed (or wall-clock start).

**D8 · Low · BUG — a failed snapshot clear leaves a resumable saved attempt.** *FIXED: snapshots carry `savedAt`; the resume offer clears a snapshot once the paper has a later-finished attempt (new `AttemptDao.getLatestFinishedAt`). `Submission`'s doc no longer claims staleness is harmless.*
`Submission.kt:31-35` swallows a clear failure and its doc claims the stale snapshot is
harmless; nothing marks the snapshot submitted and `checkResumeOffer` has no such check, so the
next same-paper test offers it and resuming+submitting writes a second attempt and re-applies
per-question stats. (Verification: save-first/clear-second ordering itself is deliberate; the
defect is the false doc claim + missing marker.) *Fix:* record the finished attempt (or clear
again when the paper's latest attempt is newer).

**D9 · Low · BUG (timing) — `prefetched` survives `resume()`.** *FIXED: `resume()` nulls the read-ahead before resolving the current card.*
`load()` prefetches with the fresh option-shuffle seed; `resume()` swaps to the saved seed
(`:825`) and `resolveCurrent` prefers `prefetched` (`:601-604`), publishing the first resumed
card with the pre-resume option order. Only the first card, only with option shuffle.
*Fix:* null `prefetched` in `resume()`.

**D10 · Low · BUG — dwell/untimed duration use the wall clock, countdown the monotonic clock.** *FIXED: dwell and the untimed duration run on `SystemClock.elapsedRealtime()`; wall time is kept only for `finishedAt` and `savedAt`.*
Countdown is anchored on `elapsedRealtime` (`:348,355`), but dwell and untimed duration use
`currentTimeMillis` (`:401,439,458,465,706`); an NTP/manual clock change distorts dwell and can
store a negative untimed duration. *Fix:* use `elapsedRealtime` for both; wall time only for
`finishedAt`.

**D11 · Low · BUG — imported `negativeMarking` is not sign-checked.** *FIXED: `sanitizedNegativeMarking` (finite, > 0, else 0.0) applied at every paper write in both importers.*
Scoring treats it as a magnitude (`Scoring.kt:16`, `HistoryStore.kt:210`); the UI cannot type a
minus but imported/restored banks are stored verbatim (`Importer.kt:145,154`;
`StreamingImportSession.kt:258`), so `-0.25` makes every wrong answer *add* 0.25×marks.
*Fix:* clamp/sanitize at the import/model boundary (`max(0.0, …)`, reject NaN/∞).

**D12 · Nit · NIT — a `,` in a category id breaks the test route.** *FIXED: category lists are double-encoded per id (`encodeCategoryList`) and decoded on parse, in the test, study and browse routes.*
`Routes.testRoute` joins ids with `,` (`Routes.kt:70-75`) and `McqNavHost` splits on `,`
(`:69`). One id `"a,b"` becomes two, possibly matching other real categories. Hand-edited banks
only. Same shape exists for the browse route.

---

### E. Statistics — from `stats_bugs.md`

**E1 · Medium · BUG — the whole review log is replayed twice on every Stats visit.**
`StatsViewModel.init` calls `refresh()` (`:88-89`), and `StatsScreen`'s ON_RESUME observer
(`:66-74`) fires immediately because the destination composes while the activity is RESUMED.
`refresh()` has no in-flight guard, so two full replays start and the first result is discarded
by the generation check. *Fix:* drop the `init { refresh() }` (observer covers first resume) or
guard with a fired-once flag.

**E2 · Medium · BUG — superseded builds keep running.**
`loadVisible` launches on `Dispatchers.Default` and keeps no Job; staleness is checked only after
completion (`:229,242,252`), and `dropSections()` (`:189-192`) only bumps the counter. Rapid
tab/range changes leave several concurrent full-log passes. *Fix:* keep and cancel the current
Job (or bail inside `replayInto`'s consume loop).

**E3 · Low · BUG — the stacked-bar overlay bridges gaps the line chart breaks.**
`StackedBarChart.kt:147` `?: return@forEach` leaves `started` true, so a null `passRate` hour is
connected straight to the next non-null point; `LineChart.kt:131-132` correctly resets. The
chart's own comment promises gap handling. *Fix:* mirror LineChart (`started = false` on null).

**E4 · Low · BUG (latent) — `niceMax` breaks its contract below 1.0.**
`ChartKit.kt:151` uses `log10(value).toInt()` (truncates toward zero), so `niceMax(0.5)` → 1.0
instead of 0.5. Every current caller coerces ≥ 1.0, so no live chart is wrong. *Fix:*
`floor(log10(value))`.

**E5 · Low · NIT — heatmap rows are not weekdays.**
KDoc says "rows Monday..Sunday" (`HeatmapChart.kt:35-36`) but the grid uses `row = index % 7`
from `todayStart − 364 days` (`:97`; `StatsAggregator.kt:499`). 364 ≡ 0 (mod 7), so row 0 is
always *today's* weekday and the mapping shifts one row per day; there is no weekday header.
*Fix:* pad the first column to true weekdays, or drop the claim.

**E6 · Low · BUG — three "overdue" boundaries on one page.**
Overview `backlog` = `dueAt < now` (`StatsAggregator.kt:399`); workload `forecast.overdue` =
`dayIndex < 0` (dueAt < todayStart, `:938-940`); cards `overdueAge` = `dueAt < now` (`:1348`). A
card due 09:00 and undone at 15:00 is backlog + overdueAge but "due today" in the forecast.
*Fix:* document the per-chart windows in help text, or align the forecast.

**E7 · Low · NIT — time-per-day tooltip prints raw seconds.**
`StackedBarChart.kt:198` prints `total.roundToInt()`; `TimePerDayCard` feeds seconds while the
axis uses `formatDuration`. A 1-hour day reads "3600" under an "1h" axis. *Fix:* an optional
tooltip-total formatter.

**E8 · Low · BUG — a failed stats load has no retry.**
`StatsScreen.kt:121-130` renders a bare error Text with no button, and `selectTab` no-ops on the
already-selected tab (`StatsViewModel.kt:145`). *Fix:* a Retry button calling `refresh()`, and/or
re-run on `selectTab` when `loadError != null`.

**E9 · Low · BUG (latent) — the per-paper table never recycles.**
`CardsSection.kt:56-58` emits `PaperCountsCard` as one LazyColumn item and `:200-202` composes
every paper's row; at the documented 1000-paper ceiling that is 1000 rows in one frame (other
ranked lists cap at 15). *Fix:* cap the table or make each paper an item.

**E10 · Nit · NIT — deleted papers show raw ids.** `StatsAggregator.kt:736,1395` fall back to the
key, so orphaned reviews surface as `paper-1698…` rows.

**E11 · Nit · NIT — junk ratings fold silently.** `FsrsReplay.gradeOf` → GOOD for out-of-range
(`:186-187`), the answer-buttons `when` → Easy (`StatsAggregator.kt:973-977`), and rating 0
counts as a pass (`StatsModels.kt:42`). Corrupt/hand-edited rows only.

**E12 · Nit · NIT — one zone offset buckets all history.** Every `load*` passes
`DayBoundary.zoneOffset(now)` (`StatsStore.kt:82,111,137,156`), so a DST/travel shift mis-buckets
reviews within an hour of the 04:00 boundary for days on the other side. Unobservable in IST;
not documented as deliberate. *Fix:* per-instant offsets or a comment.

**E13 · Nit · NIT — predicted retention truncates.** `CardInfoSheet.kt:76` uses
`(it * 100).toInt()` (0.999 → "99%"); the rest of the app rounds.

**E14 · Nit · NIT — the scope selector's paper list never refreshes.** `StatsViewModel.kt:90-93`
reads `observePapers().first()` once in `init`; a new paper is missing until the VM dies.

**E15 · Nit · NIT — KPI semantics need a qualifier.** The KPI pairs "Retention" (first-of-day
subset, `StatsAggregator.kt:375-379`) with "Reviews in range" (all, `:382`); the distinction is
only in the true-retention help string, so "92% · 148 reviews" can read as "92% of 148".

---

### F. UI layer — from `ui_bugs.md`

**F1 · High · BUG — the process-death guard fires on every recreation and destroys a live import.** *FIXED: `setNoSourceError` only fires for a VM that is still loading with nothing in it.*
`ImportScreen.kt:178-204` runs `LaunchedEffect(Unit)`, consumes the holder and, when all sources
are null, calls `setNoSourceError()` (`ImportViewModel.kt:783-790`) — but the ViewModel (keyed to
the back-stack entry) survives recreation with the preview intact. Any config change over the
preview, and every return from the preview editor, shows a Go-back-only fatal dialog that pops
the screen and loses the import. *Fix:* gate the error on "the VM has nothing loaded", or consume
the source in VM init keyed by fingerprint.

**F2 · High · BUG (needs recreation) — stale export scope falls through to `exportAll`.** *FIXED: the pending export target (paper/selection/format) lives in `LibraryViewModel`; `exportAll` has no caller on the screen any more.*
`LibraryScreen.kt:111-117` holds the pending target in plain `remember`. The system save dialog
recreates the activity; the launcher re-delivers the URI to a composition where all three vars
are null and `onExportDocument` (`:121-137`) falls into `exportAll(it)` — the whole library
written to the file the user picked for one paper/selection. `exportAll` has no legitimate caller
on this screen. *Fix:* `rememberSaveable`/ViewModel-held pending target.

**F3 · High · BUG — the countdown never restarts after a failed save.** *FIXED: the Failed branch restarts the dwell ticker and the countdown while time remains.*
`submit()` cancels `timerJob`/`dwellJob` up front; the `SubmissionOutcome.Failed` branch
(`TestViewModel.kt:743-752`) resets `saving/submitted` but never calls `startTimer()` (callers
are only `load`, `resume`, `discardResume`). The clock freezes and auto-submit is dead while the
user keeps answering; recorded duration freezes too. *Fix:* restart the timer in the Failed
branch when time remains.

**F4 · Medium · BUG (needs recreation) — a picked file is silently ignored.** *FIXED: `pendingFormat` (bookmarks) and the settings restore trio are `rememberSaveable`.*
`BookmarksScreen.kt:70-78` keeps `pendingFormat` in plain `remember`; if the activity recreates
while the picker is up, the result arrives with `pendingFormat == null` and is dropped. Settings
has the same class with its `restoreUri`/`showRestoreConfirm` confirm-dialog variant
(`SettingsScreen.kt:105-116,541-550`). (Verification correction: `pendingFormat` exists only in
BookmarksScreen, not Settings.) *Fix:* `rememberSaveable`/ViewModel-held pending choice.

**F5 · Medium · BUG — the deep-link auto-scroll re-fires on every pager generation.** *FIXED: the scroll is one-shot per focus id, flagged only once the anchor resolves.*
`BrowseScreen.kt:305-312` keys the effect on `listHasRows`; every filter change and every
`observePapers`→`bumpCategories()` rebuilds the Pager (`BrowseViewModel.kt:184-188,201-226`),
flipping `itemCount` 0→>0 and re-running the scroll. After a deep link, any filter chip or write
elsewhere yanks the list back to the focused row. (Canonical entry; the deleted
`browse_bug.md` BUG 2 describes the same defect and adds the `bumpCategories` churn note.)
*Fix:* one-shot per focus id (`rememberSaveable` flag set only when the anchor resolves).

**F6 · Medium · BUG — a paging error renders as a blank list (three screens).** *FIXED: shared `PagingRefreshErrorItem`/`PagingAppendErrorItem` (retry via `items.retry()`) wired into results, search and bookmarks.*
`ResultsScreen.kt:267-291` has no `LoadState.Error` branch; `SearchScreen.kt:101-112`'s `when`
covers only loading/empty; `BookmarksScreen.kt:127-204` never checks refresh state. A failed
refresh shows a blank area with no message/retry, and append errors are handled nowhere (the
spinner simply stops). *Fix:* a shared error row + retry via `items.retry()`, plus an
append-error footer.

**F7 · Medium · BUG — selection survives a filter change; bulk ops hit hidden rows.** *FIXED: any filter-scope change drops the selection (pruning cannot see past the loaded pages).*
`BrowseScreen.kt:106-107` holds selection; filter handlers never touch it and the chips stay
tappable in selection mode. Tick 5, switch the category, and Delete/Edit act on the invisible 5.
*Fix:* prune the selection to the visible scope on filter change (or exit selection mode).

**F8 · Medium · BUG — the test question scroll position carries over.** *FIXED: the scroll resets on question change.*
`TestSessionScreen.kt:446` uses an unkeyed `rememberScrollState()` with no reset on question
change, so question N+1 opens at the previous offset (a rotation resets it — inconsistent).
*Fix:* `LaunchedEffect(question.id) { scrollState.scrollTo(0) }` or key by id.

**F9 · Low · BUG — `openEditor` clamps a stale anchor to a wrong cursor.** *FIXED: the counted index is verified with `browseFilteredIdAt`, as `browseIndexOf` does; an out-of-scope anchor bails.*
`BrowseViewModel.kt:521` coerces `browseFilteredIndexOf(anchorId, scope)` into `0..size-1`
without verification. That function is a keyset *count* (never −1); for an out-of-scope anchor it
returns the count of in-scope rows before the anchor's position, so the editor's "N of M" and
Prev/Next start from the wrong place. The sibling `browseIndexOf` (`:502-509`) verifies with
`browseFilteredIdAt`. (Verification correction: `ui_bugs` #9's "-1 clamped to 0" mechanism is
wrong; the deleted `browse_bug.md` BUG 4 has the accurate description. Merged here.) *Fix:* apply
the same id-at-index verification and bail/fall back.

**F10 · Low · BUG — browse selection is lost on rotation.** *FIXED: selection/exports are `rememberSaveable`.* `BrowseScreen.kt:106-114`
(`selectionMode`, `selectedIds`, `exportIds`) is plain `remember`; a config change discards a
multi-select, and process death with the picker up makes export silently no-op. *Fix:*
`rememberSaveable` (save the set as an `ArrayList<String>`).

**F11 · Low · NIT — selection export is ordered by tick order.** *FIXED: rows are ordered by the browse scope's own positions; out-of-scope rows keep their relative tail order.* `BrowseViewModel.kt:622-623`
sorts by `questionIds.indexOf`, and `selectedIds` is a tick-ordered `LinkedHashSet`; a
whole-paper export sorts properly, the selection path does not. *Fix:* order by the browse
scope's own ordering.

**F12 · Low · NIT — every library dialog is composition state.** *FIXED: dialog flags, typed fields (paper/category/drill) and the drawer state are `rememberSaveable`; object-backed edit dialogs still close on recreation by design.* `LibraryScreen.kt:101-117`,
`LibraryPaperCard.kt:124-126` (drill fields), `LibraryDrawer.kt:82` are plain `remember`; a
rotation closes dialogs and discards typed input. (Verification: user-facing typed-input loss, so
arguably a BUG, but recorded at the report's NIT grade.) *Fix:* `rememberSaveable`.

**F13 · Low · BUG (needs recreation) — the pick is read in the composition scope.** *FIXED: classification and bounded reads run in `LibraryViewModel.onImportPicked`.*
`LibraryScreen.kt:98` creates `rememberCoroutineScope()` and the import-picker callback (`:175`)
runs the bounded read/classify/DOCX parse in it; a recreation cancels it with no error or
navigation. The apkg path already goes through the ViewModel. *Fix:* hand the Uri to
`LibraryViewModel`.

**F14 · Low · BUG — every bookmark toggle rebuilds the pager.** *FIXED: one Pager generation per screen — the paging sources invalidate themselves via the Room invalidation tracker (observer self-removes) and re-read the saved set per load; bookmarks and the results Saved chip both refresh in place.* `BookmarksViewModel.kt:39-52`
wraps the Pager in `flatMapLatest` over bookmark changes; each generation starts at `key = null`,
so a deep scroll jumps to the top on every toggle. `ResultsViewModel.kt:57-88` has the same class
on the "Saved" chip. *Fix:* let Room invalidation drive one Pager generation.

**F15 · Low · BUG — "Delete" is a toggle.** *FIXED: idempotent `deleteBookmark`.* `BookmarksViewModel.kt:63-65` calls
`toggleBookmark`; a second tap (row still composed) re-adds the deleted bookmark, and a row
un-bookmarked elsewhere is un-deleted. *Fix:* an idempotent `deleteBookmark`.

**F16 · Low · BUG — `AnkiNumberRow` display and stored value diverge.** *FIXED: the relearn row steps in 0.5; `AnkiNumberRow` takes a cross-field `validExtra` and never commits a value failing it (the slow row requires slow ≥ fast).* (a)
`SettingsScreen.kt:870-871` shows `relearnMs/60000.0` with `step = 1.0`, and `formatValue`
truncates (`:963-973`): 1.5 min shows "1" and stepping writes 2.0 over 90000 ms. (b)
`SpacedRepetition.kt:204` clamps slow up to fast, but the row's validity check is range-only, so
typing 5 with fast = 120 shows a phantom "5" (the StateFlow does not emit, `remember(current)`
never resets) while 120 is stored. Same widget class as H2/H3. *Fix:* step in 0.5/store seconds;
validate the cross-field rule in the row instead of sanitizing silently.

**F17 · Low · NIT — the retention slider writes DataStore per drag frame.** *FIXED: one commit per gesture via `onValueChangeFinished`.*
`SettingsScreen.kt:652-658` → `SettingsViewModel.kt:192-195` → a full DataStore edit per
`onValueChange`. (Verification remark: per-keystroke writes for text rows are documented as
deliberate at `SettingsViewModel.kt:188-191`; the slider's per-frame writes are not covered by
that rationale.) *Fix:* commit on `onValueChangeFinished` or debounce.

**F18 · Low · BUG — bookmark export opens the stream on the main thread.** *FIXED: the export runs in `Dispatchers.IO`.*
`BookmarksViewModel.kt:74-90` calls `contentResolver.openOutputStream` inside a plain
`viewModelScope.launch` (Main.immediate); Settings wraps the identical call in
`Dispatchers.IO` (`SettingsViewModel.kt:324,351`). Slow/cloud providers can jank/ANR.
*Fix:* `withContext(Dispatchers.IO)`.

**F19 · Nit · NIT — "No questions in this selection" flashes at session start.** *FIXED: a null card over a non-empty order shows a spinner; the empty text is for an empty order.*
`TestViewModel.kt:291-306` publishes `loading = false` before `resolveCurrent()` (`:312`) resolves
the first card asynchronously, so `TestSessionScreen.kt:432-441` renders the empty branch for a
frame. *Fix:* keep loading until the first publish (or treat `current == null && order.isNotEmpty()`
as loading).

---

### G. Maths tables and inline formatting — from `twelfth_bugs.md`

**G1 · High · BUG — table cell markup renders as literal tags.**
Cells deliberately carry inline markup (DOCX `paraHtml`, Anki `parseTable`, Anki export), but the
native table uses a bare `Text(cell)` (`ContentElements.kt:100-107`), the mixed view
`escapeHtmlData(cell)` (`:189`) and the HTML export `esc(cell)` (`HtmlPaperWriter.kt:253`). Only
PDF (`PdfPaperWriter.kt:510`) and the Anki export render it. *Fix:* route cells through
`InlineHtml.parse` / `renderInlineHtml` / `inlineRuns`; the image/math cases (G2/G3) need the cell
model split into blocks (or a visible placeholder).

**G2 · High · BUG — a picture in a DOCX cell renders as a base64 blob.**
`DocxReader.kt:388-394` emits a data-URI `<img>` into the cell string; both in-app table paths
render it as text (`ContentElements.kt:100,189`), dumping the base64. *Fix:* cell element model,
or strip `<img>` to a `[image]` marker as a stopgap.

**G3 · High · BUG — equations in cells render as raw MathML.**
`DocxReader.kt:395-409` puts `<math>…</math>` into the cell; the mixed view only typesets
`MathElement`s (`ContentElements.kt:184` vs `189`), and PDF's `spannedFromInlineHtml` knows no
`<math>` (`PdfPaperWriter.kt:510,623-662`). A Word maths table is unreadable in-app and in PDF.
*Fix:* same model change; for the mixed view, emit cell math through `stripMathAttributes`.

**G4 · Medium · BUG — merged DOCX cells shift every later cell a column left.**
`DocxReader.kt:499-522` ignores `w:gridSpan`; a row `[H1(span2), H2]` imports as `[H1, H2]`, so
later cells land one column left in-app, in PDF and on re-export. Vertical merge is safe.
*Fix:* read `w:gridSpan w:val` and pad with empty strings.

**G5 · Medium · BUG — mis-nested inline closes are dropped, not popped (PDF).**
`PdfPaperWriter.kt:638-646` honours a close only when it matches the stack top; for
`<u>a <b>b</u> c</b>` no underline is emitted and bold leaks over " c". `InlineHtml` pops-until-
found and the DOCX writer toggles per tag — three answers for one stored string, PDF worst.
*Fix:* mirror `InlineHtml.parse`; needs a CI Robolectric pin.

**G6 · Medium · BUG — DOCX export writes table cells as escaped plain text.**
`DocxPaperWriter.kt:585-592` uses one escaped `w:t` per cell; `inlineRuns` (which works) is never
applied to cells, so Word shows literal `<u>u</u><mark>m</mark>`. Payload round-trips
byte-stably, so this is a visible-document defect only. *Fix:* run cells through
`inlineRuns`/`ParaBuilder`.

**G7 · Low · BUG (latent) — `stripMathAttributes` drops text around a `<math>` block.**
`ContentElements.kt:295-300` keeps only the first match's value, discarding surrounding prose.
Importers currently emit pure math blocks; the structured `{"type":"math"}` shape is taken
verbatim (`LegacyParser.kt:1427-1430`). *Fix:* emit the surrounding text escaped, as
`splitTextRuns` does.

**G8 · Low · BUG — unknown tags: the screen strips, the exporters keep, and the KDoc lies.**
`InlineHtml.parse` strips unknown tags (`InlineHtml.kt:89`), while `renderInlineHtml`,
PDF and DOCX deliberately keep them visible (`RichTextHtml.kt:24-27`,
`PdfPaperWriter.kt:651-655`, `DocxPaperWriter.kt:670-672`); the class doc claims "left visible
rather than swallowed" (`InlineHtml.kt:19-21`). `Pick <one> & "all"?` loses `<one>` on screen only.
*Fix:* pick one contract (rendering unknown tags literally in-app risks wrapper-tag noise) and
align the doc/comments.

**G9 · Low · BUG — `$…$` delimiters mangle ordinary text.**
`ContentElements.kt:212` enables single-`$` inline math for body text; an imported sentence with
two `$` ("paid $5 … got $10") typesets the middle as garbled math. The HTML export does not
enable `$…$`, so screen and export disagree. *Fix:* drop the `$` delimiter or escape `\$`.

**G10 · Nit · NIT — cell newlines diverge.** `ContentElements.kt:183` converts `\n`→`<br/>` for
`TextElement`s only; cells (`:189`) collapse the break in the mixed view/HTML while native
`Text(cell)` shows it.

**G11 · Nit · NIT — consecutive `<br>`s collapse on screen but not in DOCX.**
`InlineHtml.kt:73-75` and `PdfPaperWriter.kt:634-635` suppress the second break;
`DocxPaperWriter.kt:669,707-708` emits one `w:br` per tag, so the DOCX gains a blank line.

**G12 · Nit · NIT — PDF table columns measured on encoded text.**
`PdfPaperWriter.kt:477` measures `stripInlineHtml(cell)` (tags stripped, entities not) while cells
render decoded (`:629,660`), so entity-heavy cells get over-wide columns.

**G13 · Nit · NIT — editor ragged tables.**
`EditorViewModel.kt:318-321` sizes a new row from `rows.firstOrNull()`; `:309-316` silently
no-ops `updateTableCell` for a column past the row's end. *Fix:* use the max row width and pad.

---

### H. Settings — from `settings_bugs.md`

**H1 · Medium · BUG — backup/restore failures surface under "Export failed".**
One `exportError` state serves export, backup and restore (`SettingsScreen.kt:89,95,102,546`), and
the dialog is always titled `R.string.export_failed` (`:574`). *Fix:* per-operation error slots or
an error carrying its title.

**H2 · Medium · BUG — a silently rejected typed value stays in the field.**
`sanitized()` clamps slow up to fast (`SpacedRepetition.kt:204`), but the row validates only its
own range (`SettingsScreen.kt:913`), so typing 5 with fast = 120 "succeeds": the sanitized config
equals the previous one, the StateFlow does not emit, and `remember(current)` never resets the
text. The field shows 5 while 120 is stored and every keystroke/stepper is swallowed.
*Fix:* react to the sanitized round-trip (show an error/hint) rather than silently clamping.

**H3 · Low · BUG — `fastSeconds` UI range disagrees with the domain clamp.** *FIXED: the row range is 1..3600, matching `sanitized`.*
Row range is `1f..600f` (`SettingsScreen.kt:804`), domain clamp `1..3600`
(`SpacedRepetition.kt:203`): a restored 601–3600 value renders as a permanent error with
disabled steppers, and 601–3600 cannot be typed. *Fix:* widen the row to 1..3600 (or narrow the
domain and accept the silent down-clamp).

**H4 · Low · BUG (race) — whole-config writes can revert freshly fitted weights.**
`updateSchedulerConfig` writes every key from the composition's snapshot
(`SettingsViewModel.kt:192-195` → `RepositorySettings.kt:200-214`), including `fsrsParameters`.
The tuner persists the fit with a single-key write; in the window before the flow re-emits, a
slider drag/number edit submits the old weights and overwrites the fit while the success banner
shows. `setFsrsParameters`' doc names this exact hazard. *Fix:* re-base inside the edit (write
only changed keys) or version the optimistic state.

**H5 · Low · NIT (performance) — the manifest counts attempts by listing them.**
`SettingsViewModel.kt:344` uses `getAllAttempts().size`; `Backup.kt:625` does the same in
`collect()`. `countAll()` already exists (`Daos.kt:2018-2019`) beside the "Counted in SQL"
comment. *Fix:* use `countAll()`.

**H6 · Low · BUG — no busy state/progress; leaving the screen silently cancels a restore.**
Export/backup/restore buttons are always enabled and the ViewModel marks nothing in flight
(`SettingsScreen.kt:447-471`; `SettingsViewModel.kt:273,320,394`). Leaving Settings cancels the
`viewModelScope` job — the transaction rolls back cleanly, but the restore silently never
happens; a second restore queues and wipes the first's result; a large restore looks frozen.
*Fix:* an `isBusy` state + progress, and a scope that outlives the destination (or report
cancellation).

**H7 · Nit · NIT — hardcoded labels and spliced sentences.**
Theme radio labels, the "Export Logs" chooser title and the whole advanced-Anki section are
inline literals (`SettingsScreen.kt:141,493,847-883`); six descriptions concatenate a resource
ending mid-sentence with an English tail (`:258-261,339-342,410-413,502-504,515-517,624-626,
820-822`), which would garble any translation. *Fix:* whole-sentence resources; resource the
advanced labels.

**H8 · Nit · NIT (latent) — fractional relearn minutes render truncated.**
`formatValue` with step 1.0 does `value.toInt()` (`SettingsScreen.kt:971`), and the relearn row
shows `relearnMs/60000.0` (`:870`); a restored 90000 ms shows "1" and steps from the fraction.
*Fix:* one decimal when non-integral.

**H9 · Nit · NIT — "Export Logs" can silently do nothing; `startActivity` unwrapped.**
`Logger.logFilePath()` can return null (`Logger.kt:66-91,93`) and the button's `if (path != null)`
has no else (`SettingsScreen.kt:481`); the chooser launch (`:493`) is unwrapped
(`ActivityNotFoundException` would crash). *Fix:* an else-branch message and a try/catch.

**H10 · Nit · NIT — transient dialog state lost on rotation.** *FIXED: export error, restore confirm/URI/done and the advanced section are `rememberSaveable`.*
`exportError`, `showRestoreConfirm`, `restoreDone`, `restoreUri` (`SettingsScreen.kt:89,105-107`)
and `advancedExpanded` (`:609`) are plain `remember`; a rotation dismisses the restore confirm and
loses the picked URI. *Fix:* `rememberSaveable` (custom saver for `Uri`).

---

### I. FSRS and the study loop — from `fourteenth_bugs.md`

**I1 · Medium · BUG — the optimizer's dataset deviates from the reference recipe.**
`FsrsOptimizer.kt:206-210,234-237` build the outlier buckets and the w0–w3 pretrain from every
complete card with ≥1 inter-day review, while fsrs-rs restricts both to cards with exactly one
inter-day review. On any studied library the bucket sizes/removals, `smoothAndFill` counts and
the fitted w0..w3 differ from the claimed reference; the deviation is neither documented nor
intentional-looking. *Fix:* restrict both to `deltas.count { it >= 1 } == 1`, or document the
deviation explicitly.

**I2 · Low-Med · BUG (latent API) — `FsrsReplay.replay` breaks its own id contract.**
`FsrsReplay.kt:52-53` keys two maps by row id globally and reads them back globally
(`:99-100`), while `RawReview`'s doc only requires per-question uniqueness (`:26-29`). Colliding
ids across cards overwrite each other's predictions (verified by running it: a 3-day card read a
30-day card's retrievability). Production uses `Stream` (unaffected). *Fix:* key by
`questionId to id` or demand global uniqueness in the doc.

**I3 · Low-Med · BUG — a failed pull after a persisted grade misreports and invites double-persist.**
`StudyViewModel.grade()`/`next()` (`:612-619,640-657`) wrap persist+pull in one try/catch. If
`recordStudyReview` succeeds and `pullCard()` then throws, the user sees "Could not save your
grade" (false) and the graded card stays actionable; retrying writes a second review_log row and
a second schedule advance. *Fix:* split the catches, keep the persisted result, neutralize the
grade buttons, word it "Could not load the next card".

**I4 · Low · BUG — `stop()` during an in-flight grade is undone.**
`stop()` (`:677-680`) sets `finished = true`; the completing grade coroutine's `pullCard()` sets
`finished = false` (`:319`) and serves a new card, reopening the session the learner just ended.
*Fix:* a stopped flag checked before `pullCard()`, or cancel the grade job.

**I5 · Low · BUG (race) — `pullCard`'s skip-loop is unbounded.**
`StudyViewModel.kt:289-295` `while (true) { … ?: continue }` re-pulls without changing anything if
the question vanishes between the pull's SQL read and `getQuestion`; the session then hangs with
a spinner and no error/log. The drill path is safe (`drillIndex` advances). *Fix:* bound the skip
streak (one retry, then `finishEmpty()` with a note).

**I6 · Nit · NIT — `retry()` has no in-flight guard.** `StudyViewModel.kt:267-270` nulls
`loadError` and launches `load()`; a double-tap runs two loads, both end in `pullCard()`, and
`reviewsSinceNew` increments twice. *Fix:* early-return while loading.

---

### J. Question editor — from `add_bugs.md`

**J1 · High · BUG — Delete after Prev/Next deletes the previously shown question.** *FIXED: `delete()` targets `state.questionId`.*
`moveToQuestion` retargets the same VM by updating `state.questionId` (`:626` import, `:671` DB),
but `delete()` targets the immutable constructor `questionId` (`:705-710`). Delete renders for any
`!isNew` question (`QuestionEditorScreen.kt:342`), so moving Q2→Q3 then Delete removes Q2.
*Fix:* delete `state.questionId` (and clear the session queue entry).

**J2 · High · BUG — Delete is live in the import-preview editor and deletes from the library.** *FIXED: `delete()` refuses import sessions and the button hides (`editingFromImport`).*
Import ids are never blank (`LegacyParser.kt:1371-1372`), so `isNew` is false and Delete renders;
`delete()` has no `fromImportSession` branch (unlike `save()`/`moveToQuestion`) and calls
`repository.deleteQuestion` — removing a stored row from a preview-only screen. *Fix:* hide
Delete for import sessions or make it session-aware.

**J3 · Medium · BUG — formula edits can be silently lost.**
MathLive writes MathML back 400 ms after the last input (`MathLiveEditor.kt:64-68`); `save()`
(`EditorViewModel.kt:536`) and `moveToQuestion` read state immediately with no flush, so last
keystrokes are lost and the dirty-check can read a pre-edit signature and skip the save.
*Fix:* flush the debounce before save/move (or gate on a pending-edit flag).

**J4 · Medium · BUG — stale formula after Prev/Next.**
`blockKey` is `index:kind` (`BlockListEditor.kt:172-173`), not per-load, so a Math block at the
same index keeps the same WebView and the `userEdited` latch (`MathLiveEditor.kt:114`);
`shouldPushLatex` (`:98-102`) then refuses to push the new formula. Editing what is shown
overwrites the new question's MathML. *Fix:* include the question id / load generation in the
key, or reset the latch on a different-question load.

**J5 · Medium · BUG — init failure strands the editor on "Loading…".** *FIXED: a `loadError` state renders the failure.*
`EditorViewModel.kt:238-240`'s catch only logs; `loading` is never cleared and there is no error
state, so the screen shows Loading forever with no way forward. *Fix:* set `loading = false` plus
an error field and render it (retry optional).

**J6 · Medium · BUG — process death during import editing → phantom edit; saving crashes.**
After restore, `ImportDataHolder` (statics) is empty so `fromImportSession = false`; the DB path
finds no question X and falls through to new-question defaults while keeping `questionId = X`,
`isNew = false` and `categoryId = ""` (`:196-235` only the found branch returns early). Save
upserts a question with `categoryId = ""` → FK violation (`questions.categoryId → categories.id`)
with the unguarded save → crash. *Fix:* reset `questionId = ""`/`isNew = true` when a non-blank
id is missing, or surface an error.

**J7 · Low-Med · BUG — unguarded save/delete; double-tap Save duplicates a new question.** *FIXED: both are guarded and reported via `saveError`; a `saving` flag blocks re-entry, and a new question's minted id is written back.*
`save()` (`:565-569`) and `delete()` (`:705-710`) have no try/catch (contrast `moveToQuestion` at
`:698-700`), so any Room failure crashes and `onDone()` never runs. Save is gated only on
`canSave`, not in-flight; a double-tap on a new question calls `buildDto()` twice while
`state.questionId` is blank, minting two ids (`:497`) → two identical rows. *Fix:* wrap both in
try/catch; disable Save in flight and write the minted id back.

**J8 · Low · BUG — a legacy image-only option makes the question unsavable.** *FIXED: `canSave` counts the option (and question) scalar image as content.*
An option with `image` but empty elements loads as `[TextElement("")]`
(`QuestionContentMapper.kt:50`), `isSignificant()` (`EditorViewModel.kt:42-47`) rejects it, so
`canSave` is false forever and dirty Prev/Next is blocked. The UI deliberately preserves legacy
images (`QuestionEditorScreen.kt:235-238`). *Fix:* count the legacy image in significance, or
migrate it to an Image block on load.

**J9 · Low · BUG — a question with no correct answer saves silently.** *FIXED: a visible warning renders above Save (the tolerant save itself is by design).*
`canSave` (`:85-88`) never checks `options.any { it.isCorrect }` and `toggleCorrect` allows zero
(`:350-354`), so the question persists ungraded with no warning, while the import screen flags
exactly this state (`ImportWarnings.kt:34`). *Fix:* at minimum a visible warning (the import path
deliberately tolerates the state, so a hard block would diverge — see Part 4).

---

### K. Browse extras — from `browse_bug.md`

**K1 · Low-Med · BUG — a slow refresh blanks the whole screen.**
`BrowseScreen.kt:273-281` early-returns to a "Loading" text when `refreshing && itemCount == 0`,
which is also true during every filter-change refresh; on a big paper a >300 ms search replaces
the entire Scaffold (search field, chips, dialogs) and unmounts the window effects. *Fix:* keep
the controls composed and overlay the loading state, or restrict the early return to the initial
load.

**K2 · Low · BUG — a punctuation-only tags field clears all tags.**
`BrowseViewModel.kt:688-689` does `takeIf { tagsText.isNotBlank() }` on the *unfiltered* text;
`" ,"` passes, the filtered list is empty, and `updateBulkFieldsChunked` writes an empty CSV —
deleting every tag — although the dialog says blank fields keep existing values. *Fix:*
`takeIf { it.isNotEmpty() }` on the filtered list.

**K3 · Low · NIT — transient stale `rowMoves` race.**
`refreshRowMoves` (`:300,307-308`) check-then-acts across `Dispatchers.Default` while
`invalidateContexts` (`:471-482`) bumps the generation and clears the map; pre-invalidation states
can flash for a frame or two. Cosmetic; the report itself says not worth a lock.

**K4 · Low · CLEANUP — dead browse surface.**
Both `applyCategoryFilter` overloads are test-only (`BrowseScreen.kt:1075-1092`); `swapQuestions`
has no caller (`BrowseViewModel.kt:561-566`); `RowMoveState.indexInCategory` is read only by
`BrowseRowMoveStateTest` (`:53`). Deleting them means slimming the corresponding tests.

**K5 · Nit · NIT — hardcoded browse dialog strings.**
`BrowseScreen.kt:581,650,695,759` ("Edit N questions", "Delete N questions?", …) and
`BrowseViewModel.kt:624` ("Nothing selected") are literals among `stringResource` neighbours.
(Verification: also `:781` "Select paper" and the top bar `:170-171`, not in the original list.)

---

## Part 2 — Verified fixed (history; do not re-chase)

All rows below were re-verified against the current tree. Line numbers are the verified current
locations (three original pointers had drifted and are corrected). `N/A` rows are not bugs.

### First hunt (26)

| ID | Finding | Status | Verified evidence |
|---|---|---|---|
| H1-1 | Editor could mint a duplicate option id | Fixed | `EditorViewModel.kt:357-360` first unused id |
| H1-2 | Streaming regenerated an id-less category id per call | Fixed | `LegacyParser.kt:551-554` memoised `resolvedId` |
| H1-3 | Anki `notetypes.config`/`decks.kind` parsed as JSON | Fixed | `AnkiPackageReader.kt:695-698` protobuf decode |
| H1-4 | Search left a stale `nextKey` | Fixed | `McqRepository.kt:882-884` clears before the null break |
| H1-5 | Image-only diff caused a repeating refresh | Fixed | `Importer.kt:358-359`; `Daos.kt:1047` persists the scaled image |
| H1-6 | `deleteCategory` orphaned schedules/history | Fixed | `PaperStore.kt:232-233` sweeps before delete |
| H1-7 | Cloze stem leaked the answer; index by position | Fixed | `AnkiPackageReader.kt:1160,1182` |
| H1-8 | DOCX answer separator rejected option-text answers | Fixed | `DocxParser.kt:909-930` whole-text first |
| H1-9 | DOCX field marker on its own paragraph | Fixed | `DocxPaperWriter.kt:531-535,761-766` |
| H1-10 | Streaming dropped a new paper's empty categories | Fixed | `StreamingImportSession.kt:322-331` |
| H1-11 | PDF answer key dropped legacy explanations | Fixed | `PdfPaperWriter.kt:187-191,711-719` |
| H1-12 | FSRS narrowed to `Int` before clamping | Fixed | `Fsrs.kt:261-272` clamp in `Long` |
| H1-13 | Results summary blank/markup for formula options | Fixed | `AnswerReview.kt:41` `searchableText` |
| H1-14 | DOCX VML image read `r:embed` not `r:id` | Fixed | `DocxReader.kt:446-451` |
| H1-15 | HTML counted but did not render loose questions | Fixed | `HtmlPaperWriter.kt:109-114` |
| H1-16 | HTML dropped a passage image with no elements | Fixed | `HtmlPaperWriter.kt:198-207` |
| H1-17 | PDF stripped unknown inline tags | Fixed | `PdfPaperWriter.kt:651-655` |
| H1-18 | `MediaSpool` flattened `/`→`-` (aliasing) | Fixed | `MediaSpool.kt:69-70` injective encoding |
| H1-19 | `MathMlToOmml` self-closing/whitespace edges | Fixed | `MathMlToOmml.kt:21-27,72,218` |
| H1-20 | Anki entity decoding edge cases | Fixed | `AnkiHtml.kt:24` one `#[xX]` pass |
| H1-21 | `XmlEscapingAppendable.append(Char)` returned delegate | Fixed | `DocxPaperWriter.kt:1064-1066` returns `this` |
| H1-22 | `difficulty` casing caused a one-off refresh | Fixed | `ContentHash.kt:193` case-insensitive |
| H1-23 | `withDownscaledImages` ignored `elements` | Fixed | `Importer.kt:797-808` |
| H1-24 | `TestSnapshot.reorder` did not de-duplicate | Fixed | `SessionOrder.kt:107`; dead path removed |
| H1-25 | Import button disabled for an all-duplicate backup | Fixed | `ImportViewModel.kt:250-253` `backup ||` |
| H1-26 | Foregrounding restarted a hidden dwell clock | Fixed | `StudyViewModel.kt:442-455`; `TestViewModel.kt:477-490` |

### Second hunt (39)

| ID | Finding | Status | Verified evidence |
|---|---|---|---|
| H2-1 | Anki revlog `time` read as last-review timestamp | Fixed | `AnkiPackageReader.kt:581` `max(id)` |
| H2-2 | DOCX marker guard `(a)`–`(j)` vs reader `(a)`–`(z)` | Fixed | `DocxParser.kt:658,687` shared `[a-z]` |
| H2-3 | XML control chars written verbatim | Fixed | `MathMlToOmml.kt:153` |
| H2-4 | `skipValue()` recursion unbounded | Fixed | `JsonPull.kt:109` iterative |
| H2-5 | Fresh timer auto-submitted through the Resume dialog | Fixed | `TestViewModel.kt:318-319` gate |
| H2-6 | Duplicate option ids overwrote/re-imported forever | Fixed | `LegacyParser.kt:1309-1326` `uniqueOptionIds` |
| H2-7 | Selection exports minted positional category ids | Fixed | `SelectionExport.kt:30-34` stable hash |
| H2-8 | `question_stats` never cleaned up | Fixed | `PaperStore.kt:176,237`; `QuestionStore.kt:367,381` |
| H2-9 | `LIKE` prefilter folded ASCII only | Fixed | `Daos.kt:659-664`; `McqRepository.kt:534-535` |
| H2-10 | Post-submit edits silently discarded | Fixed | `TestViewModel.kt:506,544,570` |
| H2-11 | "Export all (JSON)" on the Main thread | Fixed | `SettingsViewModel.kt:295,324` |
| H2-12 | EXIF orientation ignored | Fixed | `ImageUtils.kt:37,75-79` |
| H2-13 | Comma-stripping number filters (25× marks) | Fixed | `NumericInput.kt:15-21` |
| H2-14 | PDF block taller than a page clipped | Fixed | `PdfPaperWriter.kt:316-340` |
| H2-15 | Learning/Relearning exported as Review `ivl=0` | Fixed | `AnkiScheduling.kt:180-200` |
| H2-16 | Image-only explanation dropped from HTML | Fixed | `HtmlPaperWriter.kt:139-185` |
| H2-17 | Entity decoding disagreed across renderers | Fixed | `RichTextHtml.kt:142`; `PdfMath.kt:48` |
| H2-18 | Malformed `data:` URI dumped base64 as text | Fixed | `ExportImages.kt:31-32` bounded label |
| H2-19 | DOCX EMU width computed in `Int` | Fixed | `DocxPaperWriter.kt:878-887` `Long` |
| H2-20 | Bookmark/selection streams carried no passages | Fixed | `Exporter.kt:278-290` |
| H2-21 | Streaming dropped scheduling before papers | Fixed | `StreamingImportSession.kt:193-207,683-686` |
| H2-22 | Anki image-only stems dropped; `sfld` inconsistent | Fixed | `AnkiPackageReader.kt:880-894`; writer `:210-218` |
| H2-23 | Fallback misattributed the passage image | Fixed | `AnkiPackageReader.kt:860-862` |
| H2-24 | Test-progress header/rows non-atomic | Fixed | `TestProgressStore.kt:57,75-78` lock |
| H2-25 | Corrupt restore destroyed the live resume snapshot | Fixed | `RepositorySettings.kt:296,317-324` |
| H2-26 | Bookmarks paged by OFFSET | Fixed | `Daos.kt:299-312` keyset |
| H2-27 | `INSTR(csv)` leaked comma ids | Fixed | `McqRepository.kt:578-583` rowids |
| H2-28 | `savePaper` used `INSERT OR REPLACE` | Fixed | `PaperStore.kt:116-125` `insertIgnore` |
| H2-29 | InlineHtml style leak; chart label collision; dead DOCX branch | Fixed | `InlineHtml.kt:98-105`; `ChartKit.kt:231` |
| H2-30 | Anki writer `col.mod` ms, card id, U+001F | Fixed | `AnkiPackageWriter.kt:77,157-165,245` |
| H2-31 | Backup counts ignored paper-level questions | Fixed | `Backup.kt:634` |
| H2-32 | Post-error cleanup busy-looped | Fixed | `StreamingImporter.kt:113` |
| H2-33 | `foldPassageStrays` loaded full rows | Fixed | `StreamingImportSession.kt:603` projection |
| H2-34 | Drill "Study again" replayed stale states | Fixed | `StudyViewModel.kt:736-753` re-pull |
| H2-35 | Stats `openCard` had no generation guard | Fixed | `StatsViewModel.kt:117,126-134` |
| H2-36 | `ImageDownscale` decoded full-res; OOM passthrough | Fixed | `ImageDownscale.kt:62-78` |
| H2-37 | Crash trace lost; shared `SimpleDateFormat` | Fixed | `Logger.kt:26-27,103-119` |
| H2-38 | Comma-locale fields; default-locale format; labels; digit systems | Fixed | `HistoryScreen.kt:274`; `StorageInfo.kt:34-35`; `BrowseScreen.kt:577-638`; `SettingsScreen.kt:917-922` |
| H2-39 | ImportRunJournal mixed runs; `statsReviews` dead; dueNow | Fixed | `ImportRunJournal.kt:82-89`; `NoDeadCodeTest` guard; dueNow deliberate |

### Third hunt (8)

| ID | Finding | Status | Verified evidence |
|---|---|---|---|
| H3-1 | Study/Test order could disagree past the chunk wall | Fixed | `StudyViewModel.kt:211-218`; `CategoryFlatOrderTest` |
| H3-2 | Editor recompressed PNGs to opaque JPEG | Fixed | `ImageUtils.kt:49-56` keeps PNG |
| H3-3 | Paper-card hardcoded strings | Fixed | `LibraryPaperCard.kt:222-229`; `strings.xml` |
| H3-4 | `filterState` initial-state duplication | Fixed | `BrowseViewModel.kt:142-143` shared val |
| H3-5 | `loadContent` prune asymmetry | N/A — not a bug | `BrowseViewModel.kt:385-386` both lambdas read live `windowIds` |
| H3-6 | `jumpThreshold` magic constant | Fixed | `BrowseViewModel.kt:701,737` `JUMP_PAGES` (pointer corrected) |
| H3-7 | `statsReviews()` whole-log accessor | Fixed | `McqRepository.kt:127-135`; `NoDeadCodeTest` |
| H3-8 | `classifyPickedArchive` counted directories | Fixed | `PickedArchive.kt:46-71` |

### Fourth hunt (3)

| ID | Finding | Status | Verified evidence |
|---|---|---|---|
| H4-1 | Failed `restart()` left a spinner | Fixed | `StudyViewModel.kt:788-795` clears loading (pointer corrected); test |
| H4-2 | Silent grade/persist failures | Fixed | `StudyViewModel.kt:65,597-655`; `StudyScreen.kt:246,288` |
| H4-3 | Over-strong preview docstring | Fixed | `Fsrs.kt:21-23`; `StudyViewModel.kt:710-714` |

### Fifth hunt (3)

| ID | Finding | Status | Verified evidence |
|---|---|---|---|
| H5-1 | Countdown drifted against the wall clock | Fixed | `TestTiming.kt:41`; `TestViewModel.kt:347-356` |
| H5-2 | Rotation resurrected dialogs | Fixed | `TestSessionScreen.kt:150-152` `rememberSaveable` (pointer corrected) |
| H5-3 | Test-screen hardcoded strings | Fixed | `TestSessionScreen.kt:162-192`; `strings.xml` block |

### Sixth hunt (5)

| ID | Finding | Status | Verified evidence |
|---|---|---|---|
| H6-1 | Tracked-change insertions vanished | Fixed | `DocxReader.kt:252-281`; `DocxTrackedChangesTest` |
| H6-2 | Byte `isDocxArchive` counted directories | Fixed | `DocxParser.kt:42-63` |
| H6-3 | `jpegSize` walked markers unsoundly | Fixed | `DocxPaperWriter.kt:1135-1177`; regression test |
| H6-4 | Passage context dropped without warning | Fixed | `DocxPaperWriter.kt:169-177` |
| H6-5 | Heading-9 depth clamp | N/A — by design | `DocxPaperWriter.kt:211`; payload preserves deep trees |

---

## Part 3 — By design / not a bug (recorded so later passes do not re-chase)

These were examined and deliberately left alone. The reasoning is the source reports'; each was
re-confirmed against the code where the verdict was load-bearing.

**Test session / drill**
- `drill_preview`'s "N questions in M minutes" may under-deliver/overshoot because a passage block
  is kept whole (documented D1; a post-start `drill_overshoot_shown` banner explains it).
- Drill dialog caps count/minutes at 4 digits (9999), below `ExamTiming`'s 10080-minute clamp —
  consistent, no bug.
- Save-first / clear-second in `submitAttempt` is deliberate; only the false "stale snapshot is
  harmless" doc claim is a defect (D8).

**Stats**
- `weekdayOf` (`floorMod(epochDay + 3, 7)`) is correct (epoch day 0 = Thursday).
- Weekly buckets are Thursday-aligned; the first bucket's label is coerced to the range start.
- The first-of-day cursor is fed out-of-range rows on purpose (so a later day's first review is
  not hidden).
- `heatmap()` recomputed by the public `kpis()` — one redundant pass on the memory tab only.
- `FsrsReplay.Stream` replays in append order, not `reviewedAt` order (clock-rollback semantics,
  pinned by test).
- `gradeSeconds`/`avgAnswerSeconds` units are consistent (seconds everywhere).
- `onlyFresh` dropping sibling slices on tab switch is deliberate (prevents a stale slice).
- "Due now" excludes overdue by design (Anki-like backlog separation); the gap is a label/help
  debt, not a bug.

**Search / browse**
- `SearchPagingSource.getRefreshKey()` returning null (refresh from offset 0) is a deliberate
  search trade-off; the duplicate-key exposure until Room invalidation lands is the cost
  (recorded as a design note; see also F14/F5 for the browse variants).
- The rowid-CSV category filter cannot leak comma ids (`INSTR` on `,rowid,`).
- Browse's cursor/index agreement, paging config, search debounce, reorder math and category
  moves were checked sound.

**Settings**
- Font-scale preview math is correct under the nav-host density override.
- Retention slider granularity (0.01) matches `sanitized()`.
- `restoreBackup` reads the live `updateAnswersOnDuplicate` before applying the backup's settings
  — deliberate.
- Restore is one `withTransaction`; cancellation rolls back (the defect is feedback, H6).
- `allowLeadingMinus` on non-negative rows: a stray `-` parses null → error, no bad write.
- The restore launcher's MIME list covers mislabelled zips.
- Error callbacks invoked on `Dispatchers.IO` write Compose snapshot state (thread-safe).
- Per-keystroke config writes for text rows are deliberate ("in-progress value and stored value
  never drift"); only the slider's per-frame writes are questionable (F17).

**Rich content**
- Cell markup round-trips byte-stably through DOCX (G6 is visual only).
- `<u>`-wrapped `<img>`/`<math>` in a formatted DOCX run is a defensive branch (invalid OOXML
  that Word/LibreOffice do not emit).
- `findClosingTag`'s depth accounting is correct.
- An unclosed `<math>` in a foreign Anki field is malformed input; the DOCX "stays text" rule is
  stricter, and changing Anki risks more than it fixes.
- `mark` is hard-coded yellow/black in both renderers (readable in dark mode, matches Word's
  `w:highlight` round-trip).
- `w:shd` shading is not treated as highlight (Word shading has dozens of fills; over-highlighting
  would be wrong).
- `sanitizeMathmlReporting`'s early return skips the `mrow` wrap by design (MathJax accepts bare
  `<mi>`).
- `nbsp` decodes to a plain space in `InlineHtml` vs `\u00A0` in `RichTextHtml`/PDF — inconsistent
  but unobservable in rendering.
- Editor `blockKey` is position/kind-based by documented trade-off (content-keying had a worse
  failure); only the MathLive latch across question loads is a real defect (J4).

**Passages**
- `PassageBlocks.blockBounds`/`shuffleBlocks` are pinned against `List.shuffled(Random)`; the
  Java-RNG draw exists so a snapshot seed replays the order.
- `SessionOrder.permuted` overshoot is documented D1; published size is the block-whole size.
- `PassageReorder` row/block moves are gated on "unfiltered, blank query".
- The DOCX writer re-emitting a passage per category section is harmless (the parser compares
  passages by content canon, and an unmodified file returns the payload).
- `savePassage` keeping `existing.sortOrder` on edit is deliberate (C3 is the invisible-reorder
  defect, not this).
- `ContentHash.of` includes `passageId` so a membership-only change is not "unchanged".
- Global search's passage union and the in-memory title+body hand-off are intentional (B1 is the
  joiner mismatch).
- `PassageEditDialog` flattening rich elements when text is edited is documented; the image loss
  (C4) is a separate field and not covered by it.
- Importers' passage category + stray folding is correct (first member's category, repaired in
  the paper).
- `currentCategoryId!!` in the streaming importer is unreachable (`onCategory` always precedes a
  batch).
- `buildPassageBlocks`/`assignQuestionsToPassage`/`unassignAllFromPassage` having no production
  callers is the repository-oracle convention (`getAttemptResults`).

**Editor**
- `blockKey` content-independence for text/table/image fields is sound (only the MathLive latch is
  stateful).
- Option PKs are composite `(questionId, id)`; `addOption` now skips used ids.
- `saveQuestion` writes question+options+key in one transaction; `signatureOf` matches `buildDto`.
- The import return path is idempotent with the write-through.
- `EditorSession` cursor math is consistent; Browse's `openEditor` coerces safely (except F9).
- `mathMlToLatex` never throws; subset limits affect only initial render.
- Route ids are percent-encoded.

**FSRS / study (fourteenth report's verified-clean list)**
- `computeRemovedPairs` walk order matches upstream (the v1.1.0 difference is immaterial); quotas
  and Easy's 365 threshold match.
- `evaluateBatch`'s synthetic timeline is exact; non-finite input is walled off.
- `FsrsTuner.buildTrainingCards` ordering contract holds (keyset paging, chronological grouping,
  seeding predicate).
- The optimizer's pinned primitives (`clip`, `lrAt`, `l2Penalty`, `smoothAndFill`) match reference
  vectors; the cosine overshoot and seeded-card bucket attribution are documented deviations.
- `SettingsViewModel`'s optimize flow guards/cancels correctly; `setSchedulerConfig` sanitizes.
- Stats accumulators' arithmetic (weekday, heatmap streak, forecast bins, first-of-day cursor,
  calibration clamp, median) is correct.
- `ReplayReview`'s derived flags agree with consumers; `Stream` append order is deliberate.
- `StudyStore` counts↔pull parity, one-transaction review write, scope de-duplication hold.
- `Study.previewDelays`/`autoGrade`/`inferGrade`/`pickNext` are as documented.

**Import/export (eighth report has no not-bug section; the following were checked clean)**
- `JsonPull`'s state machine (other than A1), `SafeZip`, `BoundedRead`, paper/category identity
  merging, duplicate-content hashing and scaled-twin storage, passage flow, import counters,
  streaming batch writes.
- Anki entity decoding, marker back-parsing, media layout/dedupe, writer batching, id entropy.
- Backup transaction/wipe semantics, stream row-count checks, keyset termination.
- Export writers' `MediaSpool` injective fix, zip naming/flush order, JSON streaming shape,
  two-column PDF geometry, `OptionLetter`, XXE hardening, MathML round trip.
- Browse's `applyCategoryFilter` dead overloads are test-only by convention (K4).

---

## Part 4 — Needs device/CI/owner adjudication

| ID | Why it cannot be settled by reading alone |
|---|---|
| A6 | Latent: only a paper title containing `::` (or blank) triggers the phantom deck. |
| B4 | One-frame race; needs a UI/device reproduction to observe. |
| C6 | Needs a mistakes-only session whose passage members were missed at different times. |
| D8 | Needs a DataStore clear failure (disk full/locked) to observe the duplicate attempt. |
| D9 | Timing-dependent prefetch; needs option-shuffle on and the prefetch landed. |
| E12 | Needs a DST/travel timezone; unobservable in IST. |
| F2, F4, F10, F12, F13 | Trigger on activity recreation (rotation / process death while a picker is up); device tests recommended. |
| G5 | PDF span rendering needs a Robolectric/CI pin. |
| G9 | Needs two `$` in one text run of an imported bank. |
| H4 | Race window between the tuner's single-key write and the flow re-emission. |
| I2 | Latent batch-API contract; production uses `Stream`. |
| I5 | Needs the question deleted between the pull's two reads (concurrent restore/bulk delete). |
| J9 | Owner decision: the import path deliberately tolerates an empty answer key with a warning; should the editor warn (recommended) or block? |
| K-remark | `browse_bug.md` BUG 1 is a wrong premise (see Remarks): only a narrow concurrent-window-load race remains; decide whether it is worth a guard. |

---

## Remarks and adjudications

1. **`search_bugs.md`'s title was wrong** — it calls itself "Eighth bug report" while
   `eighth_bugs.md` is the actual eighth. Report titles/numbers were unreliable; the IDs in this
   file are canonical.
2. **`browse_bug.md` BUG 1 is a wrong premise.** It claims `requestRow`'s passage/category loads
   are pruned on arrival, but all three loaders append the fresh result *after* the
   `filterKeys { it in window }` prune (`BrowseViewModel.kt:408,432,459`), so the requested row's
   data survives. Only a narrow race remains: a still-in-flight *window* load landing later can
   prune it. Not filed as an open bug; see Part 4.
3. **`ui_bugs.md` #9's mechanism was wrong.** `browseFilteredIndexOf` is a keyset *count*
   (`browseIndexBefore`), never −1, so the claimed "-1 clamped to 0" cannot happen. The observable
   defect (an out-of-scope anchor silently yields a wrong cursor) is real; the accurate
   description is `browse_bug.md` BUG 4. Merged as **F9**.
4. **`search_bugs.md` #3's second symptom is wrong**: the "Searching…" indicator *does* show
   during the stale reload (`debouncing` compares the live trimmed query). The stale-commit defect
   itself stands (**B3**).
5. **`search_bugs.md` #7's Spacer is not literally a no-op** — it subtracts 8 dp from the
   weighted column (accidental end padding). Still dead surface (**B6**).
6. **`ui_bugs.md` #4's `pendingFormat` exists only in `BookmarksScreen`**; Settings has the
   same class via its restore confirm dialog. Corrected in **F4**.
7. **`stats_bugs.md` #3 is slightly worse than reported**: the StackedBarChart KDoc explicitly
   promises gap handling that the draw loop does not implement. **#5 is sharper**: 364 ≡ 0 mod 7,
   so row 0 is always *today's* weekday and the mapping shifts exactly one row per day (E3/E5).
8. **`add_bugs.md`'s probe test is no longer untracked** — `EditorBugHuntProbeTest.kt` was
   committed with the reports; the "untracked evidence" wording is stale.
9. **`tenth_bugs.md` #1 and `drill.md` BUG 1 are two halves of one defect**: `TestSnapshot.matches()`
   omits both the drill fields and `mistakesOnly`. Fixing one without the other leaves the
   conflation. D1 and D4 must be fixed together (extend `matches()` + `resume()`).
10. **Dedupe map for the deleted reports:** `browse_bug.md` BUG 2 = `ui_bugs.md` #5 (**F5**, browse
    version is richer); `browse_bug.md` BUG 4 = `ui_bugs.md` #9 (**F9**, browse mechanism is
    accurate); `search_bugs.md` #4 = part of `ui_bugs.md` #6 (**F6**); `ui_bugs.md` #16 and
    `settings_bugs.md` #2/#3 are the same `AnkiNumberRow` class (**F16** cross-references
    **H2/H3**).
11. **Three `bugs_all.md` evidence pointers were stale** and are corrected in Part 2: H3-6
    (`JUMP_PAGES` now `:701,737`), H4-1 (catch now `:788-795`), H5-2 (`rememberSaveable` now
    `:150-152`). All fixes themselves were present.
12. **`eighth_bugs.md` findings A1/A2/A4/A5 have committed regression tests that pin the *fixed*
    behaviour** (`JsonPullSeparatorRecursionTest`, `ImportPreviewClassificationTest`,
    `LegacyParserTest.rootQuestionsBeforePapersAddsNoPaper`). They are committed `@Ignore`d with a
    pointer to this file, so CI is green; enable them when the bugs are fixed (CI is
    authoritative). A2/A3 are pinned by CI-only `ImportPaperIdentityTest`, also `@Ignore`d.
13. **`twelfth_bugs.md`'s "Not bugs" list is correct** — none of its items maps to a filed
    finding; the list is preserved in Part 3 so it is not re-chased.
14. **One severity re-grade:** `ui_bugs.md` #12 (library dialogs in `remember`) loses typed user
    input, which is a small correctness issue rather than pure polish; kept at the report's NIT
    grade but noted in **F12**.
