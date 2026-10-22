# Category counts and the card's category picker — implementation plan

**Status:** revision 1 — planning. Branch `feature/category-card-counts`, **stacked on
`feature/category-study`** (PR #29, unmerged by instruction). Nothing merges before #29, and
nothing merges at all without the user's go.
**Follows:** `category_study.md` revision 3 (scoped Study, drawer picker, C1-C20).
**Scope:** show each category's **due / new / leech** counts, put the selection on the paper
card behind a **Show (eye)** icon, and give the ticked subtree a **full-width Study button**.

## Research facts this revision is built on

| # | Fact | Where |
|---|---|---|
| R1 | `material-icons-extended` is a direct `implementation` dependency; `VisibilityKt` is on the resolved compile classpath. `Icons.Default.Visibility` compiles with no Gradle change. | `app/build.gradle.kts:192`, resolved 1.7.8 jar |
| R2 | The per-category card-state read needs **no** category-id list: the same `FROM`/`WHERE` as `countsForPaper` plus `GROUP BY q.categoryId` binds only `:paperId` and `:now`. | `Daos.kt:1373-1396` |
| R3 | With `GROUP BY`, a paper with no rows returns **zero rows**, not one zero row — callers must default absent categories to zero. | `Daos.kt:1369-1370` |
| R4 | A paper-pinned question-count query (`JOIN categories WHERE c.paperId = :paperId GROUP BY q.categoryId`) binds one value, so a large paper never meets the bind limit; the existing `getCategoryCountsFor` is chunked only for the whole-library path. | `Daos.kt:1004-1008`, `ChunkedQueries.kt:4-14` |
| R5 | Room maps a `GROUP BY` projection to a plain data class whose property names match the aliases (`StudyPopulation`, `CategoryCountEntity` are the precedent). | `Daos.kt:1283-1290`, `Entities.kt:20-23` |
| R6 | The selection is currently drawer-local (`onByPaper`); `DrawerContent`/`PaperCard` have exactly one production call site each, so parameter changes are compile-only outside `LibraryScreen`. | `LibraryDrawer.kt:77-84`, `LibraryScreen.kt:299-341`, `:402-476` |
| R7 | Four source-shape assertions in `StudyActionStructureTest` encode the old drawer ownership and will have to be rewritten with the hoist; `studyComesFirstAndCarriesTheArrow` constrains where the new button may sit. | `StudyActionStructureTest.kt:64-139`, `:17-38` |
| R8 | `UiAccessibilityTest` forbids `IconButton(onClick = {})` and explicit touch targets below 48dp; a default `IconButton` passes. | `UiAccessibilityTest.kt:32-53` |

---

## 1. What the user asked for

> "a show button (eye icon) along with edit button which expand the paper categories
> (currently how edit button is doing), the expanded categories should have tickbox and when a
> category is selected using tickbox, there should be a button beside drill to study
> categories. also categories should display new and due leech etc separately like in paper."

Two parts of that were settled in review:

- the scoped Study button goes in the **full-width row**, not beside Drill — approved
  ("i agree with your disagreement, full width approved");
- per-category counts are the point of the feature, not decoration: tick a category and the
  button must say what that sitting will serve.

## 2. Current behaviour (verified against the tree)

| Fact | Evidence |
|---|---|
| The card's Edit icon toggles one `expanded` boolean | `LibraryPaperCard.kt:91`, `:119-124` |
| Expanded content is **management only**: per-row move/nest/promote/edit/add/delete/export icons, then Add category / Delete paper | `LibraryPaperCard.kt:271-307`, `CategoryRow` `:312+` |
| The drawer owns the selection, per paper, in a local `remember` | `LibraryDrawer.kt:77`, `onFor` `:79`, `toggle` `:81-84` |
| Drawer rows show **total questions only** | `LibraryDrawer.kt:315` — `node.totalQuestionCount` (own + descendants, `Models.kt:299`) |
| Paper badge is paper-wide and takes no scope | `StudyStore.kt:429-442` (`getStudyCounts`), `StudyCounts(due, leeches, fresh)` `:16-22` |
| `due` is learning + review; `leeches` is separate; `fresh` is gated by `introduceNewCards` | `StudyStore.kt:436-441`, `:419` |
| No per-category scheduling aggregate exists | `Daos.kt:1372-1433` are `countsForPaper`/`countsForCategories` (whole scope, five sums); the only per-category group-by is `getCategoryCountsFor` (`Daos.kt:1004-1008`), which counts **questions** |
| Study route already carries ticked ids | `Routes.kt` `studyRoute(paperId, categoryIds)`, used by the drawer at `LibraryScreen.kt:334-340` |
| Selection semantics are already written and tested | `CategorySelection.kt` — `toggle`, `tickedIds`, `orderedIds`, `states` |

So: the card can show categories, but only as a manage surface; counts are paper-wide; the
selection is invisible unless the drawer is open.

## 3. Target behaviour

1. Every paper card carries a **Show** icon button (eye) beside the Edit icon.
2. Both icons expand the same block; they differ by **mode**:
   - **Show** (eye): checkbox + title + counts per row, no manage icons, no Add/Delete;
   - **Manage** (edit): today's rows, unchanged.
   Tapping the active icon collapses. Tapping the other switches mode without collapsing.
3. Show mode renders a **tri-state checkbox per node** (the drawer's semantics:
   `CategorySelection.states`/`toggle`), so parent/child cascade behaves identically in both
   places.
4. Each row shows the node's **subtree** totals, compact and only for non-zero values:
   `4 due · 2 new · 1 tricky`. A parent shows what its whole subtree holds.
5. A ticked subtree adds a **full-width** button under the existing Study button:
   `Study selection (7 due, 3 new)` — due and new are the ticked branches' rolled-up
   totals, with zero terms omitted (`Study selection` when both are zero). Tapping it
   navigates with exactly the ticked ids, the same route the drawer uses.
6. The drawer and the card **share one selection per paper**: a tick in one is visible in the
   other, and both start the same session. The drawer's rows gain the same counts.
7. Counts update when the library resumes (same trigger as the badges today), so returning
   from a session shows the new numbers.

## 4. Design decisions

**D1 — One expansion state with a mode, not two booleans.**
`var expansion by remember { mutableStateOf(CardExpansion.Collapsed) }` with
`Collapsed | Show | Manage`. Two booleans allow "show and manage at once" and two animated
sections competing for space. The eye sets `Show`, Edit sets `Manage`, tapping the active one
sets `Collapsed`. `LibraryPaperCard.kt:91` is the only place that changes shape; the manage
block stays as it is.

**D2 — The selection moves into `LibraryViewModel`, not just up to `LibraryScreen`.**
The drawer's `remember` dies on rotation and cannot be shared with the card. The ViewModel
already owns per-paper badge state, and `papers.value` gives it the tree `CategorySelection`
needs for `toggle`. Shape:

```kotlin
private val _selection = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
val selection: StateFlow<Map<String, Set<String>>>

fun selectedFor(paperId: String): Set<String>
fun toggleCategory(paperId: String, categoryId: String)   // CategorySelection.toggle(papers.value…)
fun clearSelection(paperId: String)
```

`LibraryScreen` passes `selection[paper.id].orEmpty()` and `onToggleCategory` to both
`PaperCard` and `DrawerContent`. `DrawerContent` loses `onByPaper` and its `toggle` helper
(`LibraryDrawer.kt:77-84`); it keeps its own `expandedPaperId` (which paper is open *in the
drawer* is a drawer concern). This is the same bug class §7.1.1 of `category_study.md` fixed,
one screen up: never two selections for one paper.

**D3 — Counts are per-category own values in SQL, rolled up in the domain.**
One grouped query returns each category's *own* learning/due/leeches/newish/storedRows; a
second returns each category's question count. `fresh` is derived per category exactly as
`counts()` derives it (`questions - (storedRows - newish)`, gated by `introduceNewCards`).
The subtree rollup is a pure function over `paper.categories`, tested on the JVM, like
`totalQuestionCount` (`Models.kt:299`) but for three numbers.

Why not one query per ticked category: the button needs the rolled total *as you tick*, and a
per-tick query would put a database round-trip between the tap and the label.

**D4 — Counts load lazily, per expanded paper, on the IO dispatcher.**
`LibraryViewModel.ensureCategoryCounts(paperId)` is called when a card expands (Show or
Manage) or a drawer paper expands; results are cached in a `StateFlow<Map<String,
Map<String, CategoryCounts>>>` keyed by paper then category. `refreshStudyCounts()` clears
that cache and re-reads it for the papers whose cards are currently expanded, so the numbers
follow a session. This keeps the library's startup cost unchanged — today it already spends
one `getStudyCounts` per paper (`LibraryViewModel.kt:61-70`); we do not add two queries per
paper to every launch.

**D5 — The scoped button is full-width, directly under the paper Study button.**
The FlowRow at `LibraryPaperCard.kt:152` already holds Test, "N tricky", Export, Browse,
Drill and "Mistakes (N)", and the comment at `:127-130` records why Study left that row: a
count-bearing label resizes the button and shuffles its neighbours. A count-bearing scoped
button in the FlowRow would reintroduce that. It is only composed when something is ticked,
so an untouched card looks exactly as it does today.

**D6 — The label counts the ticked subtree, not the boxes.**
`CategorySelection.tickedIds` gives the maximal ticked branches; the scope totals are the sum
of their rolled-up counts (disjoint subtrees, so no double counting). Label:
`Study selection (7 due, 3 new)`; if only due: `Study selection (7 due)`; only new:
`Study selection (3 new)`; neither: `Study selection` (still correct: the session may serve
leeches or the learner may want to clear the queue). This mirrors the paper button's wording
at `:142-149`, so the two read as the same family.

**D7 — Show mode is read-only.**
No reorder/nest/promote/edit/delete/add/export icons in Show, and no Add category / Delete
paper row: Show is for choosing what to study. Manage keeps every existing action. This keeps
each row's width sane and stops a mis-tap from moving a category while the learner is picking.

**D8 — Copy.**
Row counts and the button label are built in the composable, like the existing Study label
(`:142-149`) and the drawer's `study_n_categories`; the ViewModel carries only numbers (C19:
unit-test Robolectric cannot resolve app resources). The eye's `contentDescription` becomes a
new `R.string.show_categories` beside `R.string.manage`; the drawer's Study/Test labels keep
their existing strings.

**D9 — Parity is a test, not a promise.**
Summing the rolled-up counts of the paper's **roots** must reproduce `getStudyCounts(paperId)`
exactly: `due`, `leeches`, `fresh`. That single assertion keeps the card, the drawer and the
badge from telling three different stories, and it is the regression that catches a future
predicate change in one query but not the other.

**D10 — A due category is still paper-scoped on write.**
Nothing changes in `recordStudyReview` (`StudyStore.kt:459`): a scoped session reschedules
exactly the cards it served, and the paper badge — and now the category counts — follow.
This is why the feature needs no session or grading change at all.

## 5. Domain — new `CategoryCounts` (pure JVM)

New file `app/src/main/java/com/mcqapp/domain/CategoryCounts.kt`:

```kotlin
/** One category's eligible cards, own values or a subtree total. */
data class CategoryCounts(
    val due: Int = 0,      // learning + review, the badge's "due"
    val fresh: Int = 0,    // new, gated by introduceNewCards
    val leeches: Int = 0   // due leeches, separate from due
) {
    operator fun plus(other: CategoryCounts): CategoryCounts
    val isEmpty: Boolean get() = due == 0 && fresh == 0 && leeches == 0
}

/** Post-order sum over the tree: node total = own + every child's total. */
fun CategoryCounts.rollUp(
    nodes: List<CategoryNode>,
    own: Map<String, CategoryCounts>
): Map<String, CategoryCounts>

/** The scope total over `CategorySelection.tickedIds` (maximal, disjoint branches). */
fun totalCategoryCounts(
    rolled: Map<String, CategoryCounts>,
    tickedIds: Collection<String>
): CategoryCounts
```

`rollUp` is a post-order sum; `totalCategoryCounts` sums distinct ids and defaults unknown
ids to zero. It deliberately does **not** re-derive maximality: the caller passes
`CategorySelection.tickedIds`, which already returns only branches whose parent is not on
(`CategorySelection.kt:61-67`), so the subtrees are disjoint. `rollUp` returns a
`LinkedHashMap` in pre-order so a composable can look up per row without re-walking.

## 6. Data layer

### 6.1 `CardStateDao` — one grouped read per paper

Added beside `countsForCategories` (`Daos.kt:1404-1433`), same five `CASE` sums and the same
paper terms, with `GROUP BY q.categoryId` and a `categoryId` column:

```sql
SELECT q.categoryId AS categoryId,
       COALESCE(SUM(CASE WHEN cs.dueAt <= :now AND NOT (cs.reps = 0 AND cs.dueAt = 0)
         AND cs.contentHash = q.contentHash
         AND cs.cardState IN ('LEARNING','RELEARNING') AND cs.leech = 0 THEN 1 ELSE 0 END), 0) AS learning,
       COALESCE(SUM(CASE WHEN cs.dueAt <= :now AND NOT (cs.reps = 0 AND cs.dueAt = 0)
         AND cs.contentHash = q.contentHash
         AND cs.cardState NOT IN ('LEARNING','RELEARNING') AND cs.leech = 0 THEN 1 ELSE 0 END), 0) AS due,
       COALESCE(SUM(CASE WHEN cs.dueAt <= :now AND NOT (cs.reps = 0 AND cs.dueAt = 0)
         AND cs.contentHash = q.contentHash AND cs.leech = 1 THEN 1 ELSE 0 END), 0) AS leeches,
       COALESCE(SUM(CASE WHEN (cs.reps = 0 AND cs.dueAt = 0) OR cs.contentHash != q.contentHash
         THEN 1 ELSE 0 END), 0) AS newish,
       COUNT(*) AS storedRows
FROM card_state cs
INNER JOIN questions q ON q.id = cs.questionId
INNER JOIN categories c ON c.id = q.categoryId
WHERE cs.paperId = :paperId AND c.paperId = :paperId
GROUP BY q.categoryId
```

- No category-id list: the paper terms scope it, so there is no bind-limit question and no
  chunking. A paper has one `paperId` index key, as `countsForPaper` relies on.
- Row type `CategoryPopulation(categoryId: String, learning: Int, due: Int, leeches: Int,
  newish: Int, storedRows: Int)` in `Daos.kt` beside `StudyPopulation` (`:1283-1289`),
  mirroring Room's existing flat-row mapping style.
- A category with no schedule rows is **absent**, not zero: the mapper defaults it. That is
  the same shape `getCategoryCountsFor` returns.

### 6.2 `QuestionDao` — question counts per category, one paper

`getCategoryCountsFor(categoryIds)` (`Daos.kt:1004-1008`) takes the paper's category list as
bind variables. A large paper can exceed SQLite's variable limit, and the counts read does not
need the list at all:

```sql
SELECT q.categoryId AS categoryId, COUNT(*) AS cnt
FROM questions q
INNER JOIN categories c ON c.id = q.categoryId
WHERE c.paperId = :paperId
GROUP BY q.categoryId
```

Add `categoryCountsForPaper(paperId): List<CategoryCountEntity>` — reuse the existing
`CategoryCountEntity(categoryId, cnt)` row (`Entities.kt:20-23`), which Room already maps from
`getCategoryCountsFor`.

### 6.3 `StudyStore` / `McqRepository`

`StudyStore.categoryCounts(paperId, now)`:

1. read the scheduler config **once** (`settings.schedulerConfigNow()`), the way `counts()`
   does, and pass that one snapshot through every step;
2. `val questions = db.questionDao().categoryCountsForPaper(paperId)` → own question counts;
   if the paper has no questions at all, return `emptyMap()` (the badge's early return,
   `StudyStore.kt:408`);
3. `val rows = db.cardStateDao().categoryPopulations(paperId, now)` → own scheduling sums;
4. per category **in either map**: `due = learning + due`, `leeches = leeches`,
   `fresh = if (config.introduceNewCards) questions[c] - (storedRows - newish) else 0` —
   the literal expression from `counts()` (`:419`), no clamp: the parity test is what proves
   the arithmetic, and a clamp on one side would hide a real divergence;
5. return `Map<String, CategoryCounts>` of **own** values for every category of the paper
   (zero-filled where a map has no row), so the rollup needs no knowledge of absence.

`McqRepository.categoryCounts(paperId, now)` is the one-line facade, beside
`getStudyCounts` (`McqRepository.kt:684-687`).

## 7. UI

### 7.1 `LibraryViewModel`

- `selection: StateFlow<Map<String, Set<String>>>`, `selectedFor`, `toggleCategory`,
  plus `clearSelection` when a paper is deleted.
- `_categoryCounts: MutableStateFlow<Map<String, Map<String, CategoryCounts>>>` and
  `ensureCategoryCounts(paperId)` with a guard so a second expand is free.
- `refreshStudyCounts()` also re-reads the cache for the papers already in it (the expanded
  ones), so a returned-from session updates both badge and rows.
- `toggleCategory` reads the tree from `papers.value`; `CategorySelection.toggle` already
  returns `on` unchanged for an unknown id, so a deleted category cannot corrupt the set.

### 7.2 `LibraryScreen`

- Collect `selection` and `categoryCounts`.
- Hoist one `onToggleCategory: (paperId, categoryId) -> Unit` and pass it to `PaperCard` and
  `DrawerContent`; pass `selected = selection[paper.id].orEmpty()` and
  `categoryCounts = categoryCounts[paper.id].orEmpty()`.
- `onStartScopedStudy = { paperId, ids -> navController.navigate(studyRoute(paperId, ids)) }`,
  logging like the drawer's `:336`.
- `onExpandCategories = { viewModel.ensureCategoryCounts(paper.id) }` for both surfaces.

### 7.3 `PaperCard`

New parameters (all defaulted, so previews/other call sites keep compiling):

```kotlin
selected: Set<String> = emptySet(),
onToggleCategory: (String) -> Unit = {},
onStartScopedStudy: (List<String>) -> Unit = {},
categoryCounts: Map<String, CategoryCounts> = emptyMap(),
onExpandCategories: () -> Unit = {}
```

- `expansion` state (D1). The eye button (`Icons.Default.Visibility`, `testTag("paper-show-<id>")`,
  contentDescription `R.string.show_categories`) sits immediately left of the Edit button
  (`:119-124`). R1 confirms the icon compiles; R8 means the default 48dp `IconButton` is the
  right container and it must never be composed with an empty `onClick`.
- `expanded` becomes `expansion != Collapsed`; the expanded `Column` branches on mode:
  - `Show` → `ShowCategoryRow` per node, using `CategorySelection.states(paper.categories,
    selected)` computed once per (tree, selection) like `LibraryDrawer.kt:195-200`, and the
    rolled-up counts; no buttons;
  - `Manage` → the existing `CategoryRow` block and Add/Delete row untouched.
- The scoped button, when `tickedIds` is non-empty:

```kotlin
Button(
    onClick = { onStartScopedStudy(tickedIds) },
    modifier = Modifier.fillMaxWidth().testTag("paper-study-selection-${paper.id}")
) { Text(scopedLabel) }
```

placed directly below the paper Study button (`:131-150`) — full width, before the FlowRow.

- Row copy: `"$title"` plus non-zero terms `" · N due"`, `" · N new"`, `" · N tricky"` in a
  `labelSmall` secondary style, so a row with no counts stays a plain title.

### 7.4 `DrawerContent`

- Signature: replace the local state with
  `selectedByPaper: Map<String, Set<String>>` and
  `onToggleCategory: (paperId: String, categoryId: String) -> Unit`; keep `onStartStudy`,
  `onStartTest`, `onStartTest`, `onImport`.
- Delete `onByPaper`, `onFor`, `toggle` (`:77-84`); compute `on = selectedByPaper[paper.id]
  .orEmpty()` per item. **Keep the local name `on`**: `theCategoryTreeUsesATriStateCheckbox`
  asserts the exact text `CategorySelection.states(paper.categories, on)` (`:89`), and this
  keeps that assertion honest without editing it.
- Keep the drawer's button copy (`R.string.study_n_categories` / `test_n_categories`) and the
  `ticked.size` label: `theDrawerLabelsTheTickedBranchCount`, `theDrawerHasNoHardCodedActionLabels`
  and the device test's `"Study (1 categories)"` assertion all pin it. The card's scoped label
  is a separate, count-bearing control.
- `CategoryTree` gains `counts: Map<String, CategoryCounts>` and appends the same compact
  count label; it gains `testTag("drawer-counts-<id>")` so a device test can read a row.
- Call `onExpandCategories()` when a paper row expands, next to `expandedPaperId` (`:205-208`).

### 7.5 `strings.xml`

Add `show_categories` ("Show categories"); everything else uses existing keys
(`categories`, `manage`, `study_n_categories`) or the inline label pattern the card already
uses.

## 8. Edge cases and invariants

1. **A count is never larger than what the session serves.** The `fresh` gate and the
   leech/due split are copied from `counts()`; the parity test is the guard.
2. **Ticked subtree that excludes a child.** `CategorySelection.toggle` writes the concrete
   child off state, so the parent turns indeterminate and leaves the scope; the button label
   sums only the maximal ticked branches. Unchanged from #29.
3. **Whole tree ticked.** `tickedIds` returns the roots; the label equals the paper badge
   (`getStudyCounts`); the session is the paper-wide session.
4. **Selection survives collapse, rotation and drawer close** (it lives in the ViewModel).
   `Delete paper` clears that paper's entry.
5. **Category deleted while ticked.** `papers` re-emits; `selectedFor` may hold a dead id.
   `CategorySelection.tickedIds`/`orderedIds` ignore unknown ids, `<n>` counts only known
   branches, and the route expansion drops it. No crash, no phantom scope. (Optional: prune on
   `papers` emission; not required.)
6. **Question moved out of a category / stale hash.** The card_state join follows the question;
   `newish` counts a hash mismatch as new, exactly like `counts()`. A question moved to
   another paper stops counting here, which is also why the parity check is by roots.
7. **New cards switched off.** `fresh` is 0 in rows and badge alike; the button then shows
   only due/leeches, and `Study selection` alone if those are 0 too.
8. **Empty paper / no categories.** The eye still opens an empty block; no button, no crash.
9. **A big paper.** Counts are two grouped reads per expanded paper, both keyed on `paperId`;
   nothing is materialised per question.
10. **An unreachable category (dangling `parentId` or a cycle).** `PaperStore.buildTree` roots
    at `parentId == null`, so such a category is absent from `Paper.categories` while
    `getStudyCounts` still counts its questions. The rollup then totals less than the badge.
    `Paper.totalQuestions` has the same blind spot today, so this is pre-existing and not a
    regression; the parity test uses a well-formed tree, and the fixture asserts every
    category id appears exactly once in `CategorySelection.flatten` before asserting parity.

## 9. Tests

**9.1 Pure JVM — `CategoryCountsTest.kt`**
- rollup: leaf own values survive; a parent sums children and own; two roots stay disjoint;
- empty maps, unknown ids, category absent from `own` (defaults to zero);
- `totalCategoryCounts(tickedIds)` ignores duplicates, defaults unknown ids to zero, and sums
  the maximal branches (the set `CategorySelection.tickedIds` returns);
- label inputs: due+new, due only, new only, both zero.

**9.2 Robolectric (CI x86_64 authoritative) — `CategoryCountsQueryTest.kt`**
Fixture: one paper, roots `A` (own 2 questions) and `B` (child `B1`), questions with card
states covering due review, due learning, due leech, fresh, and an edited (stale) card.
- per-category own counts match the fixture;
- `rollUp` over roots equals `getStudyCounts(paperId)` for due, leeches, fresh (D9);
- `B` includes `B1`; `B1` alone excludes `B`'s own questions;
- with `introduceNewCards = false`, fresh is 0 on every row and the badge.

**9.3 Structural — extend `StudyActionStructureTest.kt`**
- New: the eye button exists (`paper-show-`) and the scoped button (`paper-study-selection-`)
  is `fillMaxWidth` **after** the whole-paper Study button and **before** the first `FlowRow(`
  — the three ordering assertions of `studyComesFirstAndCarriesTheArrow` (`:17-38`) must still
  hold, so the new button may not contain `onClick = onStudy` or precede the play arrow;
- Rewrite `theCategoryTreeUsesATriStateCheckbox` to point at **both** files (the drawer keeps
  `states(paper.categories, on)`; the card composes the same map for its Show rows);
- Rewrite `theDrawerKeepsASelectionPerPaper` as "one selection owner": `LibraryViewModel`
  declares `selection`, `LibraryScreen` passes it per paper, and `LibraryDrawer.kt` contains
  neither `var onByPaper` nor a second `mutableStateOf` map;
- `theDrawerOffersStudyBesideTest`, `theDrawerHasNoHardCodedActionLabels` and
  `theDrawerLabelsTheTickedBranchCount` stay, because the drawer keeps its Study/Test buttons
  and their copy.

**9.4 Device — extend `StudyAndSelectionUiTest.kt`**
- expand a card with the eye, tick the parent, assert the button label carries the fixture's
  due/new, tap it, assert the study screen's scope label;
- tick in the card, open the drawer, assert the same node is checked (one selection);
- untick, assert the button disappears.

**9.5 Updated tests**
- `StudyActionStructureTest` as in §9.3;
- `LibraryViewModelStudyCountsTest` if the ViewModel's constructor/state shape is touched (it
  builds the ViewModel directly, so keep the new flows additive);
- `StudyAndSelectionUiTest.deletingAPaperConfirmsAndReportsTheLoss` keeps working because
  Edit still opens Manage, which still contains Delete paper;
- `RoutesTest` untouched — the route shape does not change.

**9.6 Must keep passing untouched**
`CategorySelectionTest`, `ChunkShapeTest`, `StudyPullQueryCountTest`, `TestCategoryScopeTest`,
`StudyCategoryScopeTest`, and `getStudyCounts`'s existing tests.

## 10. Implementation order

**Phase A — data (no UI change)**
1. `CategoryCounts` + `rollUp` + `totalFor` (domain).
2. `CategoryPopulation`/`CategoryCountRow` + both DAO queries.
3. `StudyStore.categoryCounts` + `McqRepository.categoryCounts`.
4. `CategoryCountsTest` (JVM) and `CategoryCountsQueryTest` (Robolectric).
Green line: compiles locally; the JVM test runs locally; the DB test is CI-authoritative.

**Phase B — UI**
5. `LibraryViewModel`: selection + counts cache + refresh + delete cleanup.
6. `PaperCard`: expansion modes, eye button, Show rows with ticks and counts, full-width
   scoped button.
7. `DrawerContent`: selection params, counts, expand callback.
8. `LibraryScreen`: wiring for both surfaces and the scoped route.
9. `strings.xml` `show_categories`.
10. Structural + device tests; update the affected ones.
Green line: compile locally, device tests on CI.

## 11. Risk register

| Risk | Mitigation |
|---|---|
| Two selections disagree (the §7.1.1 bug again) | D2: one map in the ViewModel; structural test §9.3 |
| Card/count mismatch with the badge | D9 parity test over the roots, CI-run |
| Counts go stale after a session | refresh on `ON_RESUME` re-reads the cached papers |
| Library startup slows down | D4: per-expanded-paper only |
| Show mode becomes a manage surface | D7: no manage icons in Show; structural test |
| Button label resizes neighbours | D5: full width, outside the FlowRow |
| Room cannot map the grouped row | flat row class in `Daos.kt` like `StudyPopulation`; Phase A compile proves it |
| Bind-limit on a huge paper | 6.1/6.2 use `paperId`, never a category list |
| An orphan category makes roots < badge | pre-existing `Paper.totalQuestions` blind spot; documented in §8.10, fixture asserts a well-formed tree |
| Source-shape tests rot with the hoist | §9.3 rewrites them in the same commit as the UI change |

## 12. Acceptance criteria

1. A paper card shows an eye button; it expands a read-only tree with a checkbox per node.
2. Each row shows its subtree's due / new / leech counts, omitting zeros.
3. Ticking any node (parent or child) shows a full-width `Study selection (…)` button whose
   numbers equal the ticked subtree's rolled-up counts.
4. The button starts a scoped Study session with exactly the ticked ids, and the session's
   scope label matches the selection.
5. The drawer shows the same counts and the same ticks for the same paper, and its Study/Test
   buttons keep working.
6. Sum of the roots' counts equals `getStudyCounts(paperId)` at every fixture: the CI test
   proves it.
7. Manage mode is unchanged in behaviour.
8. No new dependency; no `includeAndroidResources`; no ViewModel `getString`.

## 13. Out of scope

- Per-category **mistake** counts (the card's `Mistakes (N)` stays paper-wide).
- A scoped Test button on the card (Test stays in the drawer).
- Reordering or editing from Show mode.
- Persisting the selection across app restarts (it survives rotation/navigation via the
  ViewModel; a restart starts clean).
- Changing `getStudyCounts` to take a scope (L1 in `category_study.md`).

---

## 14. Revision 2 — scope-aware Export and Test (shipped on this revision's branches)

Shipped: Phases A + B merged to `main` (PRs #29, #30, release `0.0.29`). This section plans
the follow-up that makes the card's remaining actions selection-aware. User ruling: *"existing
export button is used — if category is selected, share category; if no category is selected,
share whole paper. And do the same thing with Test, so a user can test some categories."*

### 14.1 Design

**E1 — The existing `Export` button becomes scope-aware.** No ticks → whole paper (today's
behaviour). Ticks → the union of the ticked branches. Zero new buttons; the per-row Share in
Manage is **removed** (its job is now "tick it in Show, tap Export"), taking `CategoryRow`
from eight icon buttons to seven and losing its only non-edit action.

**E2 — No new exporter code.** `Exporter.categoryStream(paperId, rootCategoryIds: Set<String>)`
already takes a set, expands every id's subtree (`CategoryFilter.subtreeIds`), and walks the
union; `PaperExporter.exportStream` renders any such walk in every format. A new thin
`exportSelection(paperId, categoryIds, format, sink, twoColumnPdf)` wraps:
`categoryStream` → `takeIf { it.categories.isNotEmpty() }` → `render(..., ::loadScheduling, ...)`,
throwing "The selected categories have no questions to export." when empty — the ViewModel's
catch surfaces it in the existing error dialog.

**E3 — The label stays `Export`; the dialog states the scope.** A count-bearing label in the
FlowRow resizes and reshuffles its neighbours — the documented reason Study left that row
(`LibraryPaperCard.kt:127-130`). The format dialog's title already switches per target; it
becomes `"Export selection (N categories)"` (N = ticked branches, the drawer's wording) vs
`"Export <paper title>"`. File name: `fileNameFor("${paper.title} (selection)", format)`.
`pendingExportCategory` (Triple) is replaced by `pendingExportSelection: Triple<paperId,
tickedIds, count>`; the single-category path through `exportCategoryAs` and the dialog's
"Export <category>" branch are retired with the per-row Share.

**E4 — The existing `Test` button becomes scope-aware the same way.** Ticks →
`testRoute(paperId, tickedIds)` (the drawer's exact path, `LibraryScreen.kt:330`); none →
whole paper. Label stays `Test`, tag `paper-test-<id>` unchanged (device tests keep passing);
the test session is the existing scoped one, so no confirmation surface is added on the card —
parity with the drawer, which already starts scoped tests from a `Test (N categories)` button.

**E5 — Parity statement.** `tickedIds` are maximal branches; `categoryStream` expands
subtrees. Export and Test therefore act on exactly the scope the scoped Study button serves.

### 14.2 Work items

1. `PaperExporter.exportSelection` (E2); `exportCategory` stays (bookmarks/other callers).
2. `LibraryViewModel.exportSelectionAs(uri, paperId, categoryIds, format)` beside
   `exportCategoryAs`; `twoColumnPdf` from the same setting.
3. `LibraryScreen`: `pendingExportSelection` replaces `pendingExportCategory`; dialog title
   and file name per E3; confirm routes to the new VM method.
4. `PaperCard`: `onExport: (List<String>) -> Unit` (ticked ids) and a new
   `onScopedTest: (List<String>) -> Unit`; Test `onClick` branches on `ticked`; delete the
   per-row Share button and `onExportCategory`'s threading through `CategoryRow`.
5. Tests: structural (`exportAndTestFollowTheSelection`: both handlers branch on `ticked`;
   `Icons.Default.Share` absent from the card; the ordering test's `onClick = onStart` literal
   is rewritten for the branching form); device (tick → Export → dialog title names the
   selection → cancel; tick → Test → only the scope's questions appear, modelled on
   `studyFromTheDrawerServesOnlyTheSelectedCategory`); exporter (a Robolectric DB test of
   `exportSelection` over two branches: category set, question count, and the empty-selection
   error); `CriticalPathTest` untouched (tag/label unchanged).
6. README: one line under the scoped-study feature — card Export/Test follow the selection.
