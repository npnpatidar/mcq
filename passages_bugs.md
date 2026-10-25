# Ninth bug report — scoped pass: the passages feature

A scoped pass over the passages feature — **status:** report only, nothing
fixed yet. Files covered: the domain rules (`PassageBlocks.kt`,
`PassageReorder.kt`, `SessionOrder.kt`, `TestSnapshot.kt`, `Passage` in
`Models.kt`), the store layer (`PassageStore.kt`, `QuestionStore.kt`,
`PassageEntity.kt`, `PassageDao` in `Daos.kt`, `QuestionContentMapper.kt`,
`HistoryStore.kt`), the screens (`PassagesScreen.kt`, `EditorViewModel.kt`,
`QuestionEditorScreen.kt`, `BrowseViewModel.kt`, `BrowseScreen.kt`,
`TestViewModel.kt`, `TestSessionScreen.kt`, `StudyViewModel.kt`,
`StudyScreen.kt`), and the passage paths of import/export/Anki/docx
(`Exporter.kt`, `PaperStream.kt`, `SelectionExport.kt`, `Importer.kt`,
`StreamingImportSession.kt`, `HtmlPaperWriter.kt`, `PdfPaperWriter.kt`,
`DocxPaperWriter.kt`, `DocxParser.kt`, `AnkiDtoMapper.kt`,
`AnkiPackageWriter.kt`, `AnkiPackageReader.kt`).

Already-known findings are not repeated: the import-side passage-ownership bug
(`eighth_bugs.md` #3), the HTML empty-passage image drop (`bugs.md` #16,
verified fixed at `HtmlPaperWriter.kt:198-207`), bookmark/selection export
omitting passages (`new_bugs.md` #20, verified fixed in `Exporter.kt:273-281`
and `SelectionExport.kt:55-67`), and the `foldPassageStrays` full-row read
(`new_bugs.md` #33, verified fixed by `getMemberCategoriesByPassage`,
`Daos.kt:1112-1116`). What follows is new.

**Confidence legend:** **Confirmed** = defect proven by reading the current
code (finding 3's REPLACE rowid behaviour was also verified by running SQLite);
**Likely** = code defect real, one reachability step depends on data shape. This
host is aarch64 and cannot run Robolectric, so SQLite-backed claims are
CI-authoritative; as with the previous scoped reports, nothing was fixed and no
tests were added.

---

## Summary

| # | Sev | Area | Bug | Conf. |
|---|-----|------|-----|-------|
| 1 | Medium | Data / Browse | Bulk "move to category" moves a passage member alone — no category enforcement, no re-placement — and a later save does not heal it | Confirmed |
| 2 | Medium | Data / Browse | Bulk "copy to category" keeps `passageId` with no enforcement or placement: cross-category copies are strays, same-category copies append outside the group | Confirmed |
| 3 | Medium | Passages screen | Up/down arrows change a `sortOrder` the list query never reads; the REPLACE-upsert then makes an edited passage jump to the end of the list | Confirmed |
| 4 | Medium | Passages screen | Editing a passage silently deletes its image, even on an untouched save | Confirmed |
| 5 | Low | Browse | The header's "first member" lookup uses a different order than the list, so a split group draws its header on the wrong row | Confirmed |
| 6 | Low | Session | Mistakes-only sessions are ordered most-recently-missed-first, so a passage set can be split by the shuffle/drill despite the whole-block rule | Likely |
| 7 | Low | Editor | The passage picker is hidden when the category has no passages, so a stray member cannot be unassigned from the app | Confirmed |
| 8 | Nit | Passages screen | Edge reorder taps are enabled and silent; the flat list and per-category reorder disagree; new passages all get `sortOrder = 0`; a category change keeps the old `sortOrder` | Confirmed |
| 9 | Nit | Passages / DAO | `PassageDao.updateFields` (which also skips the search columns) and `insertIgnore` are dead; `buildBlocks`' KDoc names callers that do not exist; `toPassageDto` is `suspend` for no reason | Confirmed |

---

## Findings

### 1 · MEDIUM · Data/Browse — bulk "move to category" strands passage members

The Browse bulk action calls `viewModel.moveQuestions(...)`
(`BrowseScreen.kt:813-815`), which is `repository.moveQuestionsToCategory`
(`BrowseViewModel.kt:553-559`). That function changes `categoryId` only — one
`updateCategoryChunked` (`QuestionStore.kt:405-434`). It never moves the member
to the passage's category and never re-places it next to the group.

Everything else enforces the invariant this path drops:

- `savePassage` moves every member when the *passage* changes category and
  re-places them (`PassageStore.kt:74-83`);
- `saveQuestion` calls `enforcePassageCategory` + `placeWithGroup` when the
  assignment changes (`QuestionStore.kt:287-290`);
- `assignQuestions` does the same (`PassageStore.kt:121-139`);
- the importer folds strays into the passage's category
  (`StreamingImportSession.kt:592-612`).

Impact:

- the group is split, possibly across papers (a cross-paper move also wipes the
  moved members' card state and review log, `QuestionStore.kt:419-431`);
- the passage row itself stays in the source category, so
  `getPassagesForPaper`/the editor's category filter no longer offer it in the
  member's new category — the question shows "No passage" while it still
  points at one (`EditorViewModel.kt:218`, `QuestionEditorScreen.kt:205-212`);
- `saveQuestion`'s enforcement is gated on the assignment *changing*
  (`existing?.passageId != question.passageId`, `QuestionStore.kt:287`), so
  opening and saving the stranded member does not move it back;
- the passage can no longer be deleted: `deletePassage` refuses while any
  member remains (`PassageStore.kt:97-106`);
- the session block builder now sees two runs (`PassageBlocks.kt:31-42`), and
  the Browse header lookup goes wrong (#5).

Moving **every** member is not a workaround: the passage row still names the
old category. Only editing the passage's category (dialog or import) moves the
row, and nothing moves it with its members except `savePassage`.

**Fix.** In `moveQuestionsToCategory`, after the category write, either redirect
members whose passage lives elsewhere (apply the `enforcePassageCategory` rule)
or move the passage row with them and re-place the whole group, mirroring
`savePassage`. The bulk-move UI should then say which one happened; silently
doing neither is the present bug.

### 2 · MEDIUM · Data/Browse — bulk "copy to category" creates out-of-place members

`copyQuestionsToCategory` rewrites only `categoryId` on each clone
(`QuestionStore.kt:437-455`); `insertCopies` carries `passageId = q.passageId`
and appends each row at the target's tail (`QuestionStore.kt:304-354`). No
`enforcePassageCategory`, no `placeWithGroup`.

- **Partial selection:** the copied members form a second run in the target
  while the rest stay behind, so both categories hold part of the group.
- **Same-category copy:** the clones append after the category's last question,
  i.e. outside the group — the passage now has two runs in one category.
- **Full cross-category copy:** the new run is contiguous, but the passage row
  still belongs to the source category, so the same ownership break as #1
  applies (picker, delete guard, header lookup).
- In every case the source passage's member count grows, so `deletePassage`
  stays refused (`PassageStore.kt:97-106`) for copies the user may not even
  recognize as members.

Editing a clone later does not repair it either: `existing.passageId` and the
edited `passageId` are equal, so `saveQuestion`'s enforcement does not run.

**Fix.** A copy is new content, not a new member: drop `passageId` on the clone
when the target category differs from the passage's, or route the whole
selection through `assignQuestions` semantics. Dropping is the smaller change
and matches the "members share one category" invariant.

### 3 · MEDIUM · Passages screen — reorder arrows write a field the list does not read

- The screen lists `repository.observePassages()` (`PassagesScreen.kt:112`),
  which is `passageDao.observeAll()` (`PassageStore.kt:29-30`).
- `observeAll` is `SELECT * FROM passages` with no `ORDER BY`
  (`Daos.kt:176-177`), and so is `getAll` (`Daos.kt:173-174`).
- The arrows call `movePassage` (`PassagesScreen.kt:257-272`), which renumbers
  `sortOrder` within the category (`PassageStore.kt:173-188`).
- The only reader that honours that order is `getByCategory`
  (`Daos.kt:170-171`), used by `getPassagesForPaper` (the editor's picker) and
  by `movePassage` itself.

So pressing up/down changes the picker order but leaves the management list
exactly as it was; the control has no visible effect where it is offered.

The order the screen does show is insertion (rowid) order, and
`PassageDao.upsert` is `@Insert(onConflict = REPLACE)` (`Daos.kt:206-207`) on a
table whose primary key is a TEXT id: REPLACE deletes and re-inserts the row,
which assigns a fresh rowid (verified by running it: `a,b,c` at rowids 1-3,
re-save `a`, the scan becomes `a@4, b@2, c@3`). Editing any passage therefore
moves it to the **end** of the list — a second, independent way the screen's
order ignores the field the arrows maintain.

**Fix.** Add `ORDER BY sortOrder, rowid` to `observeAll` (and `getAll`), or sort
the emitted list on the screen. That also makes the rowid effect harmless (it
still happens, but is no longer observable); a comment on the REPLACE is worth
keeping because it explains the reordering during an edit.

### 4 · MEDIUM · Passages screen — editing a passage drops its image

`PassageEditDialog` builds the saved object from title, text and category only
(`PassagesScreen.kt:490-498`):

```kotlin
onSave(
    Passage(
        id = initial?.id.orEmpty(),
        categoryId = categoryId,
        title = title.trim(),
        elements = elements
    )
)
```

`Passage.image` defaults to null (`Models.kt:282`), and `savePassage` writes the
incoming entity as-is (`PassageStore.kt:61-64`). Neither the text field nor the
"elements unchanged" branch carries `initial.image`, so **every** save from this
dialog — including opening the editor and pressing Save untouched — wipes the
passage's picture. Passages with images are ordinary: both importers carry
`passageImage` (`Importer.kt:881-897`,
`AnkiPackageReader.kt:460-468`), and Browse, Study and Test all render it
(`BrowseScreen.kt:1063`, `StudyScreen.kt:569`, `TestSessionScreen.kt:1049`).

The dialog's deliberate flattening of rich elements when the text is edited
(`PassagesScreen.kt:477-489`) explains the markup loss, not this: the image is a
separate scalar and is dropped even when the text is not edited at all.

**Fix.** Pass `image = initial?.image` (and expose it if editing is wanted). If
the dialog is meant to be text-only, at least carry the existing image through.

### 5 · LOW · Browse — the header's first-member lookup uses a different order

`getFirstRefIdByPassage` is `WHERE passageId = ? ORDER BY sortOrder, rowid
LIMIT 1` (`Daos.kt:736-740`). Browse is ordered by
`categories.sortOrder, categories.title, questions.sortOrder, questions.rowid`
(`Daos.kt:674-675`), as is every other passage-member query
(`getRefsByPassageOrdered` 749-759, `pagingRefsByPassage` 765-775,
`getMemberIdsByPassage` 777-785). The KDoc states the assumption that makes the
shorter order safe — "a passage's members share one category" — which is exactly
what #1/#2 break.

With a group split across categories, the chosen id can belong to a category
that appears later in the list. `BrowseScreen` draws the passage card only where
`state.passageFirstIds[passageId] == question.id` (`BrowseScreen.kt:478-482`), so
the header lands on a later row — above a row that is already inside the group —
while the true first member gets only the "in passage" label
(`BrowseScreen.kt:499-510`).

**Fix.** Make the lookup agree with the display order (join `categories` and
order by its `sortOrder`/`title`), and delete the one-category assumption from
the KDoc. That also renders legacy stray data correctly, before #1/#2 are fixed.

### 6 · LOW · Session — mistakes-only sessions can split a passage set

The mistakes source is ordered most recently missed first
(`HistoryStore.kt:484-493` → `getMistakenQuestionIdsForPaper`,
`Daos.kt:1952-1960`, `ORDER BY r.id DESC`). `SessionOrder.of`
(`SessionOrder.kt:194-198`) then builds blocks from *runs* of equal `passageId`
(`PassageBlocks.blockBounds`, `PassageBlocks.kt:31-42`), and both `shuffled` and
`sampled` permute those runs (`SessionOrder.kt:76-89,131-146`). Two members
missed at different times are separated by other misses, become two blocks, and
can be placed far apart — contradicting "a drill samples whole blocks … rather
than split a passage across the cut" (`PassageBlocks.kt:6-21`).

Severity stays low: the test screen renders the passage for every member, so the
question remains answerable. What is lost is the stated ordering guarantee and
the D1 "the last block may overshoot rather than split" promise.

**Fix.** Group members when building a mistakes order (stable partition by first
occurrence, keeping the recency order of the groups), or document the exception
next to the D1 comment.

### 7 · LOW · Editor — a stray member cannot be unassigned

The question editor renders the passage dropdown only when the *current
category* has passages (`QuestionEditorScreen.kt:208`), and "Remove from
passage" is an item inside that dropdown
(`QuestionEditorScreen.kt:512-519`). A question whose `passageId` points at
another category's passage (the state #1/#2 create) resolves to "No passage"
(the id is not in the filtered list); when the target category has no passages,
the dropdown — and with it the only unassign action — is not composed at all.
Changing the question's category drops the assignment as a side effect
(`EditorViewModel.kt:258-263`), which is a surprising way to have to do it.

**Fix.** Show the picker whenever `state.passageId != null`, even with no local
choices (the "None" row is then the only entry), or add a standalone "Remove
from passage" action outside the dropdown.

### 8 · NIT · Passages screen — small reorder/creation rough edges

- The up/down arrows are never disabled at a category edge; `movePassage` clamps
  and returns `false` (`PassageStore.kt:179-180`), so the tap is a silent
  no-op — unlike the question rows, which disable at the edge via `canMove`.
- The list is flat and paper-mixed (`PassagesScreen.kt:231`), while
  `movePassage` reorders only within one category: moving a passage past a
  neighbour from another paper does nothing visible (compounded by #3).
- New passages get `sortOrder = 0` (default `Models.kt:283`; the dialog passes
  none, `PassagesScreen.kt:490-498`), so every fresh passage ties and the
  category order falls back to rowid until one is moved.
- Changing a passage's category in the dialog keeps its old `sortOrder`
  (`PassageStore.kt:63`), so it can collide with the target category's
  positions; `movePassage` heals that only when an arrow is pressed later.

### 9 · NIT · Passages / DAO — dead and misleading surface

- `PassageDao.updateFields` (`Daos.kt:212-219`) and `insertIgnore`
  (`Daos.kt:209-210`) have no callers. `updateFields` is also a trap: it writes
  neither `searchText` nor `searchTextFolded`, so a future caller would leave
  global search stale for that passage.
- `PassageStore.buildBlocks`' KDoc calls it "the shape Browse and the
  test-session block builder both need" (`PassageStore.kt:190-194`), but Browse
  uses `PassageReorder` plus on-demand rows and the session uses `SessionOrder`.
  Only tests call it (through `McqRepository.buildPassageBlocks`). Keeping it as
  the tests' oracle is the codebase convention (`getAttemptResults`); the doc is
  simply wrong about the callers.
- `PassageEntity.toPassageDto()` is declared `suspend` (`Exporter.kt:435`) but
  suspends on nothing, and the modifier propagates into streaming writers.

---

## Not bugs (checked and deliberately left alone)

- **`PassageBlocks.blockBounds` / `shuffleBlocks`** — pinned by
  `PassageBlocksTest` against `List.shuffled(Random)`; the Java-RNG draw exists
  so a snapshot seed replays the presented order.
- **`SessionOrder.permuted` overshoot** — documented D1; the `take` pass and
  the `written` pass agree, so the published size is the block-whole size.
- **`PassageReorder` row/block moves** — pinned by `PassageReorderTest`; the row
  arrows are gated on "unfiltered, blank query" (`BrowseScreen.kt:289-292`), so
  a filtered window can never renumber against a hidden neighbour.
- **The docx writer re-emitting a passage once per category section** — the
  parser compares passages by content canon, order-independently
  (`DocxParser.kt:157-166`), and an unmodified file returns the payload rather
  than the visible parse, so a round trip does not multiply passages. (I
  suspected it would; the payload path rules it out.)
- **`savePassage` keeping `existing.sortOrder`** — deliberate: an edit must not
  move the row (its current invisible reorder is #3, not this). The member move
  on a real category change (move, then sequential `placeWithGroup`) is correct.
- **`ContentHash.of` includes `passageId`** (`ContentHash.kt:29-39`) — an import
  that only changes membership is not mistaken for "unchanged".
- **Global search's passage union** — SQL matches `passages.searchTextFolded`
  (`Daos.kt:663-664`) and the in-memory post-filter is handed the passage
  title+body so a hit through the passage is not vetoed
  (`McqRepository.kt:271-284`).
- **`PassageEditDialog` flattening rich elements when the text is edited** —
  documented and shared with the question editor; the image loss (#4) is a
  different field and not covered by that decision.
- **Importers' passage category and stray folding** — `paperPassageRecords`
  takes the first member's category and `foldPassageStrays` repairs the rest
  inside the paper (`StreamingImportSession.kt:332-337,592-612`).
- **`currentCategoryId!!` in the streaming importer** (`:412`, `:462`) —
  `onCategory` always precedes a question batch, so it is unreachable.
- **`buildPassageBlocks` / `assignQuestionsToPassage` / `unassignAllFromPassage`
  having no production callers** — they are the repository oracle the passage
  tests use, the same convention as `getAttemptResults`.
