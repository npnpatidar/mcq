# Category-scoped Study — implementation plan

**Status:** revision 3 — **Phases 1-3 (`1c36973`), the Phase 4 fix (`1831058`) and Phase 5
(`97402c6`) are committed**; Phase 4's CI run is red on two points, both now explained. The
scoped label and empty-reason copy went through `getString`, which a Robolectric unit test
cannot resolve (C19), so the copy moves to the screen. `TestCategoryScopeTest`'s ungraded
count asserted 0 on a **graded** question; it now proves the count with a no-key question.
Phase 3's green is CI-authoritative for `StudyCategoryScopeTest` and the two
`SessionOrderLoadTest` cases, which are Robolectric. Facts re-verified against `c4a8f07`;
the 20 audit rulings, C1-C5, C6-C8, C9-C13, C14-C18 and C19 are applied. See
"Revisions — what changed and why" below.
**Scope:** let a learner start a Study session over **one or more categories** of a paper,
including **all descendants of any selected parent**, without touching Test mode's data or
any existing schedule.

---

## Revisions — what changed and why

Revision 2 applies the audit rulings (R1-R20) and the five Coder corrections (C1-C5);
revision 3 adds the runner and tooling facts (C6-C8).

| Finding | Change | Section |
|---|---|---|
| **B1** | Selection model replaced with a per-node concrete tri-state that propagates upward. `expand(ticked ids)` is no longer the state model; ticking a child of a ticked parent is no longer a silent no-op. | §3.1, §4.1, §4.2, §9.1 |
| **H1** | `getOrderForCategories` uses `getByIdsChunked` **and** takes `paperId`, closing N3 in the same edit. | §7.1.2, §12 |
| **H2** | `countForCategoriesChunked` added to the helper list and to the dispatch table. | §5.4, §5.5 |
| **M1** | The extra chunk constant is deleted. The existing 500-variable primitive is reused; the "one slot for `:now`" arithmetic is corrected to `ids + 3`. | §5.4 |
| **M2** | Both ViewModel snippets used `getApplication<Application>().getString(...)`. **Reversed by C19**: the accessor is untestable here, so the ViewModel carries typed state and `StudyScreen` resolves the copy. | §7.3, §7.4 |
| **M3** | Copy rule is now "insert into the `WHERE`, before any `ORDER BY`/`LIMIT`", with a worked `LIMIT 1` example; the five `WHERE` line numbers are corrected. | §5.2 |
| **M4** | New D9: the route carries **ticked** ids, the destination expands. The snapshot-size and resume-matching reasons are recorded. | §3 (D9), §6, §8.1 |
| **M5** | Every target in the deleted scale-notes document is dropped; the file was removed in `c4a8f07`, so the plan states its budgets inline. | §10, §12 |
| **L1** | `getStudyCounts` no longer takes `categoryIds`. Scoped parity is asserted through `getStudyEligibility`; §9.3 was the only caller. | §5.5, §9.3 |
| **L2** | Only `BrowseScreen.flattenCategories` is lifted. `subtreeIdsOf` stays `internal` with its `null`-means-no-filter contract, as a thin delegating wrapper. | §4.1 |
| **L3** | Child order is credited to `CategoryDao.getByPaper`'s `ORDER BY sortOrder, title`, not to `buildTree`, which does not sort. | §4.1 |
| **L4** | `StudyPopulation` has five fields; the chunk sum covers all five. | §5.4 |
| **L5** | Cross-chunk `bestReview` tie-break nuance recorded; no parity claim. | §5.4, §12 |
| **L6** | Wall-clock test replaced by a deterministic 10k-node correctness test. | §9.1 |
| **N1** | Chunk order restated: one chunk serves the paper-wide order restricted to the scope; larger scopes are chunk-major. Nested papers assert sets only. The false "`c` is fixed per category" sentence is gone. | §5.4, §9.3 |
| **N2** | Same as M4: the header rewrite per answer and `TestSnapshot.matches` set equality are the routing rationale. | §3 (D9), §6, §8.1 |
| **N3** | The live cross-paper vector is `getOrderForCategories → getByIds → pageSessionRefsByCategory`; `getRefsByCategories` hardening is defence in depth only. | §7.1.1, §7.1.2 |
| **N4** | The bind-limit proof is a chunk-shape test, not a 901-id DB test (Robolectric SDK 34 defaults to 32766 variables). | §5.4, §9.3 |
| **N5** | Three pre-order copies acknowledged. Only the Browse one is lifted; the other two stay, with the reason recorded. | §4.1 |
| **N6** | Button labels count **ticked branches**, not expanded ids. | §7.1 |
| **N7** | `resetEdited` stays paper-wide: it is bounded by edited rows, so a scoped branch buys nothing. | §5.5, §8.1 |
| **N8** | The invented "`StringResourceUsageTest` forbids inline strings" justification is removed; extraction is for i18n. | §7.1 |
| **N9** | §14 corrected: drills are category-scoped, the mistakes path ignores `categoryIds`. | §14 |
| **N10** | Destination-id change from the new `?categories=` segment and its back-stack consequence recorded. | §8.9, §12 |
| **N11** | Deep-recursion risk recorded; the 10k-node test is wide, not deep. | §9.1, §12 |
| **C1** | H1 gets an owner (Phase 3) and its four call sites are listed, including the facade; §9.8 splits "updated" from "untouched". | §7.1.2, §9.8, §11 |
| **C2** | The chunk-shape proof moves to a new pure-JVM `ChunkShapeTest`; `ChunkedQueriesTest` is Robolectric, so the local run never executed it. | §5.4, §9.3, §11 |
| **C3** | The worked example uses the real column `cs.cardState`, and the ORDER BY count is three of five, not five of five. | §5.2 |
| **C4** | `theDrawerKeepsASelectionPerPaper` is stated as a source-shape assertion; the behavioural version moves to androidTest. | §9.6, §9.7 |
| **C5** | Phase 3/4 green lines say "compiles locally, executes on CI x86_64" for Robolectric classes. | §11 |
| **C6** | The local runner skips five real test classes because it greps the word `Robolectric` anywhere, comments included; the definition of a local green is corrected and the phase lines stop claiming those classes ran. | §9.10, §11 |
| **C7** | `AGENTS.md`'s fenced build command — not only the bullet — points at the dead `/tmp/ncal-aapt2/aapt2`; §10 drafts a replacement block plus both bullets (the runner-skip instruction included) for owner approval. **Applied after approval**; §10 records the shipped text and the patched runner selector. | §10 |
| **C8** | The durable runner fix (select on `^import org.robolectric`) is a recommended follow-up in §12, not a change made here, because the script lives outside the repo. | §12 |
| **C9** | §9.3's descendant row is corrected: the store is flat, so the test expands `[cP]` first and also asserts the parent id alone yields 0 — the expansion is proved load-bearing. | §9.3 |
| **C10** | `bestDueReviewForCategoriesChunked` takes the scheduler the pull already read instead of re-reading settings, so one pull ranks with one config snapshot. | §5.5 |
| **C11** | The H1 filter is on the category rows (`filter { it.paperId == paperId }`), not on refs, which carry no paper field; `loadOrder` pages per category, so the two are equivalent. | §7.1.2 |
| **C12** | §9.8 records the two cross-paper regression cases and the `p2` fixture, with CI-authoritative green wording. | §9.8 |
| **C13** | The scoped pull's cost is stated as a bound (`1 + 3 * ceil(scope/500)`, at most 7 for 501 categories) rather than "extend the test"; the observed value is recorded on CI in Phase 6. | §9.3, §11 |
| **C14** | Both drill call sites pass `scopeIds` — `load()` and `restart()`; missing `restart()` would widen a scoped tricky drill on "Study again". | §7.3 |
| **C15** | `RoutesTest` is Robolectric (it calls `Uri.encode`), so no Robolectric class is locally green on this host; Phase 4's green is compile-locally, CI-authoritative, with the SIGABRT evidence recorded. | §9.5, §11 |
| **C16** | §9.10 separates the two facts: the selector picks the only subset this host can run, and the real defect is that prose can hide a pure-JVM class. | §9.10 |
| **C17** | Test mode's expansion is a named Phase 4 step, with ticked ids kept for `matches`/the header, and the zero-cards consequence stated. | §6.4, §11 |
| **C18** | A `TestViewModel`-level regression test (`TestCategoryScopeTest`) pins the expansion; named in §9.8 and in Phase 4's test list. | §9.8, §11 |
| **C19** | `getApplication<Application>().getString(...)` is untestable in this project: `app/build.gradle.kts:151-153` sets `testOptions { unitTests.all { maxHeapSize } }` with no `includeAndroidResources = true`, and no `test_config.properties` is generated anywhere under `app/build`, so Robolectric runs with framework resources only and every app-string lookup throws `Resources$NotFoundException`. In `load()` that exception is caught and becomes `loadError`, so `current` is never set; in `finishEmpty()` it is thrown before `_state.update`, so `finished` is never set — exactly the two Phase 4 CI failures. Copy moves to `StudyScreen.kt`; the ViewModel keeps typed `EmptyReason`/`ScopeLabel`. Not fixed by enabling `includeAndroidResources`: that changes the environment of all 113 unit-test classes for one feature and still leaves copy in the ViewModel. | §7.3, §7.4, §9.4, §11 |

---

## 1. Current behaviour (verified against the tree)

Study is scoped by paper id only. There is no category concept anywhere in the study
stack.

| Fact | Evidence |
|---|---|
| Study route carries no categories | `Routes.kt:28` — `studyRoute(paperId, leechesOnly)`; nav route `study/{paperId}?leechesOnly={leechesOnly}` (`McqNavHost.kt:151-152`) |
| Test route *does* carry categories | `Routes.kt:33` — `testRoute(paperId, categoryIds, …)` → `&categories=…` |
| Library Study buttons pass paper id only | `LibraryScreen.kt:411`, `:416` |
| Drawer category tree starts a **test** only | `LibraryDrawer.kt:213` — `onStartTest(paper.id, selected.toList())`; callback at `LibraryScreen.kt:326` |
| Study store has no category parameter | `StudyStore.kt` — `grep -i category` returns nothing |
| Study DAO queries are paper-scoped | `Daos.kt:1333, :1348, :1364, :1382, :1407` — `WHERE cs.paperId = :paperId` |
| New-question DAO queries are paper-scoped | `Daos.kt:469, :518, :537` — `WHERE categories.paperId = :paperId` |
| Drawer checkboxes do **not** cascade | `LibraryDrawer.kt:249` `CategoryTree` toggles `node.id` only |
| The drawer's selection is one global set | `LibraryDrawer.kt:63`, unfiltered at `:207`, passed whole at `:213` |

So today: *Test* can be started per category; *Study* cannot, and the category picker has
no parent/child cascade.

### 1.1 Where a category already reaches questions

`getSessionOrderForCategories` (`McqRepository.kt:466-467`) → `getOrderForCategories`
(`QuestionStore.kt:117-119`) → `db.categoryDao().getByIds(categoryIds)` → `loadOrder`
(`QuestionStore.kt:139`) → `pageSessionRefsByCategory` (`Daos.kt:423-430`). None of those
steps filters by paper: the category ids are trusted as already belonging to the paper.
That trust is the live cross-paper vector (§7.1.1), not a hypothetical one.

`getRefsByCategories` (`Daos.kt:399`) also filters `questions.categoryId IN (:categoryIds)`
with no paper predicate, but its only production caller is `observeRefsForPaper`
(`QuestionStore.kt:96-98`), which passes the paper's own ids. It is a latent shape, not the
serving path.

---

## 2. Target behaviour

1. The drawer's per-paper category tree gains a **Study** action beside **Start test**.
2. Selection is **multi-select** (already possible) and **cascading**:
   - ticking a parent ticks its whole subtree;
   - unticking a parent unticks its subtree;
   - a partially-ticked parent renders **indeterminate**;
   - unticking one child of a parent makes the parent indeterminate;
   - ticking the last missing child makes the parent checked;
   - ticking every child by hand makes the parent checked and puts the branch in scope.
3. Starting Study with a selection serves **only cards whose question sits in the expanded
   set** — the ticked categories plus every descendant.
4. Selecting **every** category of the paper behaves exactly like the paper's Study button.
5. Nothing selected → the Study button is not offered (the Test button already behaves this
   way, `LibraryDrawer.kt:208`).
6. The paper card's Study badge stays **paper-wide** and is unaffected by category sessions.
7. Study never writes attempts, so Test results, History and mastery are untouched.

---

## 3. Design decisions

| # | Decision | Recommendation | Why |
|---|---|---|---|
| **D1** | Does the cascade apply to **Test** as well? | **Yes.** One tree, one selection model. | A checkbox tree that cascades for Study but not Test is unexplainable. Both actions expand the same set. Document the change in README (Test-by-category now includes descendants). |
| **D2** | Descendant questions included? | **Yes**, always. | Matches the user request and the export/`CategoryFilter` precedent (`CategoryFilter.kt`: "descendants included"). |
| **D3** | Empty selection | Study button hidden; paper card Study remains whole-paper. | Mirrors `LibraryDrawer.kt:208`'s Test button. |
| **D4** | Leeches + categories | Categories and `leechesOnly` **intersect**. | The tricky drill on a subtree = "my problem cards in this subject". |
| **D5** | Library badge | Stays **paper-wide**. | Counts are computed once per paper (`LibraryViewModel.kt:64`); a per-selection badge would need a query per keystroke. Optional follow-up (see §14). |
| **D6** | Route encoding | Mirror `testRoute`'s `joinToString(",")` + `Uri.encode`. | Consistency; see the known comma limitation in §8.7. |
| **D7** | `IN (:ids)` > SQLite variable limit | **Chunk at 500**, the limit the app already uses. | minSdk 26 ships SQLite with a 999-variable cap; `ChunkedQueries.kt:13` already owns the budget. |
| **D8** | Scope label in the Study header | 1 ticked branch → its title; N → "N categories". | The header already shows `state.paperTitle` (`StudyScreen.kt:445`). The count is the branch count, like the button (§7.1). |
| **D9** | Route payload | **Ticked ids**, not the expanded scope; the destination expands from `Paper.categories`. | The Test header JSON is rewritten on every answer (`TestProgressStore.kt:75-79`), so an expanded scope costs O(categories) per tap; and `TestSnapshot.matches` compares ids as sets (`TestSnapshot.kt:47-48`), so a stored expanded set stops matching when the tree changes. Both destinations already load the paper (`TestViewModel.kt:199`, `StudyViewModel.kt:165`). |

### 3.1 Selection model (concrete per-node tri-state)

The drawer stores, per paper, the set of nodes that are **on**. A node's checkbox is derived
from its children, not from "some ancestor is ticked":

- a **leaf** is on iff the learner ticked it;
- a node **with children** is on iff every child is on; it is unchecked iff none is; otherwise
  it is indeterminate and is not on.

`toggle(id)` writes the whole subtree on or off, then recomputes every ancestor bottom-up. That
is the property revision 1 lacked: revision 1 stored "explicitly ticked" ids and derived the
effective set as `expand(explicit)`, which is downward-closed, so removing a child from the
explicit set re-ticked it through its ticked parent. Here the child keeps its concrete OFF and
the parent simply stops being on.

Consequences, all of them intentional:

- **Scope = the nodes that are on.** An **indeterminate branch contributes only its fully-on
  descendants, never itself**: its own questions are out of scope while one of its children is
  off.
- **A branch whose whole subtree is on is in scope**, including the branch's own questions.
  This is what makes "tick every category" equal the paper-wide session even when the learner
  ticks leaves one by one: the last tick propagates up and turns every ancestor on (AC 10).
- **Deleted ids are inert.** The stored set may name ids that no longer exist; `states`,
  `tickedIds` and `orderedIds` walk the loaded tree only, and `toggle` on an unknown id is a
  no-op.

---

## 4. Domain layer

### 4.1 New file: `app/src/main/java/com/mcqapp/domain/CategorySelection.kt`

Pure, no Android types, no DB. This is where the cascade lives so it is unit-testable
without Robolectric (which matters: this host cannot run DB tests, see `AGENTS.md`).

**Reuse, don't reinvent.** Browse already solved "a node plus its descendants" over a
flattened tree:

- `BrowseScreen.kt:1029` — `flattenCategories(nodes, depth)` builds `(node, depth)` pre-order.
- `BrowseScreen.kt:1069` — `subtreeIdsOf(flat, rootId)` returns the subtree by scanning
  forward until the depth drops back to the root's; `null` means "no filter", an unknown id
  means the empty set.

Lift **only** `flattenCategories` into `CategorySelection`. Keep `subtreeIdsOf` where it is,
`internal`, with the same signature, as a one-line delegation to the lifted implementation —
`BrowseViewModel.kt:231` calls it across files and `BrowseCategoryFilterTest.kt:7` imports it,
so moving it would churn both for no gain.

Two other pre-order copies stay, deliberately: `BrowseViewModel.kt:652` (a private copy that
feeds the filter dropdown) and `QuestionEditorScreen.kt:565` (builds a `FlatCategory`, a
different shape). Lifting them would rewrite two more call sites for no benefit to this
change; the lifted function's KDoc names them so the next editor can find them.

```kotlin
package com.mcqapp.domain

/** Checkbox state of one node in the category tree. */
enum class CategoryCheckState { CHECKED, UNCHECKED, INDETERMINATE }

/**
 * Cascading selection over a paper's category forest.
 *
 * [on] holds the nodes whose own box is on. [toggle] maintains the invariant
 * that a node with children is on exactly when every child is on, so an on
 * node's whole subtree is on and can be re-expanded from its id alone. A
 * partially selected branch is never on; only its fully-on descendants are.
 */
object CategorySelection {

    /** Pre-order `(node, depth)` flattening. */
    fun flatten(nodes: List<CategoryNode>): List<Pair<CategoryNode, Int>>

    /** [rootId] and every descendant, in pre-order. Unknown id -> empty. */
    fun subtree(nodes: List<CategoryNode>, rootId: String): List<String>

    /**
     * Browse's contract: [rootId]'s subtree ids, or null when [rootId] is null
     * ("no filter"). An unknown id yields the empty set.
     */
    fun subtreeIds(flat: List<Pair<CategoryNode, Int>>, rootId: String?): Set<String>?

    /**
     * The ids a route carries: the maximal on nodes — every on node whose
     * parent is not on — in pre-order. Re-expanding these with [orderedIds]
     * reproduces the same scope exactly (§6.1).
     */
    fun tickedIds(nodes: List<CategoryNode>, on: Set<String>): List<String>

    /**
     * The scope a session serves: every id inside a ticked id's subtree, in
     * pre-order. Unknown ids contribute nothing.
     */
    fun orderedIds(nodes: List<CategoryNode>, tickedIds: Collection<String>): List<String>

    /**
     * Tick or untick [id]: write [id] and its whole subtree on or off, then
     * recompute every ancestor bottom-up (all children on -> on, none -> off,
     * otherwise the ancestor is not on). Unknown id -> [on] unchanged.
     */
    fun toggle(nodes: List<CategoryNode>, on: Set<String>, id: String): Set<String>

    /** One node's state. O(subtree); use [states] when rendering a whole tree. */
    fun state(nodes: List<CategoryNode>, on: Set<String>, id: String): CategoryCheckState

    /** Every node's state, derived from [on] in one pass over the tree (§4.2). */
    fun states(nodes: List<CategoryNode>, on: Set<String>): Map<String, CategoryCheckState>

    /** Title of [id] anywhere in the forest, or null. */
    fun titleOf(nodes: List<CategoryNode>, id: String): String?
}
```

Algorithm notes:

- `flatten` is the lifted `BrowseScreen.flattenCategories`; it is recursive today and stays
  recursive (N11: a 10k-*deep* chain would overflow, as the existing composition already
  would; the scale test is wide, not deep).
- Children arrive already ordered: `CategoryDao.getByPaper` is
  `ORDER BY sortOrder, title` (`Daos.kt:55`), and `PaperStore.buildTree` (`:77-92`) only
  groups the list, preserving that order. `buildTree` does **not** sort — the invariant lives
  in the query and belongs in its KDoc.
- `toggle` needs the pre-order list plus a parent index. Write the subtree with a forward
  scan (stop when depth ≤ the node's own), then walk to the root recomputing each ancestor:
  `on(ancestor) = children.all { it.id in on }`. A child that is indeterminate is not on, so
  the ancestor is not on either. Cost: O(subtree + depth).
- `tickedIds` is a second forward scan: emit `id` only when it is on and its parent is not on.
- `orderedIds` is a forward scan with an "inside a ticked subtree" flag; it never walks a
  subtree per id.
- `titleOf` searches the forest; `""` when the id is not in the tree (a deleted category in an
  old route).

### 4.2 How `states()` stays linear

The stored set is the truth, so classification is a fold, not a per-node subtree walk:

1. **Reverse pass** over the pre-order list (children before parents):
   `subtreeSize(node) = 1 + Σ subtreeSize(child)`,
   `onCount(node) = (1 if node.id in on else 0) + Σ onCount(child)`.
2. **Forward pass**: `CHECKED` when `onCount == subtreeSize`, `UNCHECKED` when `onCount == 0`,
   `INDETERMINATE` otherwise.

Under the §3.1 invariant `id in on` and `onCount == subtreeSize` are the same predicate; the
count is used because it also answers "how much of this branch is on" and because it makes the
derivation independent of how `on` was produced (a hand-built fixture in a test, or a route
re-expanded at the destination).

Two O(n) passes, no `state(id)` calls while rendering, and the classification cannot disagree
with the scope: `orderedIds` includes exactly the nodes classified `CHECKED`.

Invariants worth a unit test: every node's state matches its membership in `on`; and
`orderedIds(n, tickedIds(n, on)).toSet() == on` for any reachable `on` — the D9 round trip,
asserted by `reExpandingTheTickedIdsReproducesTheScope` (§9.1).

### 4.3 `SpacedRepetition.kt`

**No change to the scheduler.** `Study.pools` / `pickNext` / `onlyLeeches`
(`SpacedRepetition.kt:355-390`, `:431`, `:490`) already operate on whatever candidate list
they are given. The scoping is a query concern, not a memory-model concern.

One addition, used by the cross-chunk pick and added in **Phase 3**:

```kotlin
/**
 * The same comparator Study.pools applies to due reviews, exposed so a caller
 * that read one candidate per chunk can pick the global winner without
 * reimplementing "lowest retention, then oldest due".
 *
 * A tie on (retention, dueAt) keeps the order the candidates were supplied in.
 * The SQL tie-break (authored order) can therefore differ across a chunk
 * boundary; see §5.4.
 */
fun bestReview(
    scheduler: Scheduler,
    candidates: List<StudyCard>,
    now: Long
): StudyCard?
```

Implemented as `pools(scheduler, ids, states, now).reviews.firstOrNull()`, so there is still
one comparator in the app.

---

## 5. Data layer (Room)

### 5.1 Why new DAO methods and not a nullable parameter

A nullable list cannot be expressed safely in Room:

```sql
AND (:categoryIds IS NULL OR q.categoryId IN (:categoryIds))   -- IN () is a syntax error
```

Room expands an empty collection to `IN ()`, which SQLite rejects. A discriminator boolean
(`:allCategories = 1 OR …`) still has to bind the list. So the scoped path gets **its own
methods**, mirroring the existing `…ForPaper` / `…ForCategories` naming already used for
imports (`countUngradedForCategories`, `getRefsByCategories`).

The paper-wide methods stay **byte-for-byte unchanged**, so the hot path keeps its current
plan and every existing test keeps its oracle.

### 5.2 `CardStateDao` additions (`app/src/main/java/com/mcqapp/data/local/Daos.kt`)

**Rule: insert one predicate into the existing `WHERE` clause, immediately after the
`c.paperId = :paperId` term, and rename the method.** Do **not** append at the end of the
annotation: three of these five queries end in `ORDER BY` (two of them `… LIMIT 1`), and
SQLite accepts a predicate placed there *silently* — it becomes part of the ordering
expression instead of filtering. Verified by the auditor on SQLite 3.x (`node:sqlite`):
`... ORDER BY rowid_` followed by `rowid_ AND categoryId IN (?)` returns every row
unfiltered, and `LIMIT 1 AND 0` returns none. The failure mode is wrong results, not a build
error, so the rule is mechanical.

All five already join `categories c` and already pin `c.paperId = :paperId`. The insertion
point is the line cited:

| Existing query | `WHERE` line |
|---|---|
| `countsForPaper` (`Daos.kt:1311-1335`) | `:1333` |
| `staleForPaper` (`Daos.kt:1342-1355`) | `:1348` |
| `dueForPaper` (`Daos.kt:1358-1372`) | `:1364` |
| `firstDueLearningForPaper` (`Daos.kt:1375-1394`) | `:1382` |
| `bestDueReviewForPaper` (`Daos.kt:1397-1422`) | `:1407` |

Worked example on the `LIMIT 1` query, because that is where the mistake is invisible.
Every identifier below is the real one from `Daos.kt:1382-1388` — this predicate is
`cs.cardState`, not `cs.state`, and the example is only useful if it is copyable:

```sql
-- firstDueLearningForPaper today (Daos.kt:1382-1388)
WHERE cs.paperId = :paperId AND c.paperId = :paperId
  AND cs.dueAt <= :now AND NOT (cs.reps = 0 AND cs.dueAt = 0)
  AND cs.contentHash = q.contentHash
  AND cs.cardState IN ('LEARNING','RELEARNING')
ORDER BY c.sortOrder, c.title, q.sortOrder, q.rowid LIMIT 1

-- firstDueLearningForCategories
WHERE cs.paperId = :paperId AND c.paperId = :paperId
  AND q.categoryId IN (:categoryIds)            -- inserted here, before ORDER BY
  AND cs.dueAt <= :now AND NOT (cs.reps = 0 AND cs.dueAt = 0)
  AND cs.contentHash = q.contentHash
  AND cs.cardState IN ('LEARNING','RELEARNING')
ORDER BY c.sortOrder, c.title, q.sortOrder, q.rowid LIMIT 1
```

```kotlin
// countsForPaper + the predicate on the WHERE line.
@Query(<countsForPaper verbatim, predicate inserted at :1333>)
suspend fun countsForCategories(
    paperId: String,
    categoryIds: List<String>,
    now: Long
): StudyPopulation

// staleForPaper, predicate at :1348.
suspend fun staleForCategories(
    paperId: String,
    categoryIds: List<String>
): List<QuestionIdHash>

// dueForPaper, predicate at :1364; ORDER BY untouched.
suspend fun dueForCategories(
    paperId: String,
    categoryIds: List<String>,
    now: Long
): List<CardStateEntity>

// firstDueLearningForPaper, predicate at :1382.
suspend fun firstDueLearningForCategories(
    paperId: String,
    categoryIds: List<String>,
    now: Long
): CardStateEntity?

// bestDueReviewForPaper, predicate at :1407; the retention expression and the
// `cs.dueAt ASC, c.sortOrder, c.title, q.sortOrder, q.rowid` tie-break are untouched.
suspend fun bestDueReviewForCategories(
    paperId: String,
    categoryIds: List<String>,
    now: Long,
    dayMs: Double
): CardStateEntity?
```

Do **not** retype the annotations from memory — copy the text and move one term, or the
aliases (`q` vs `cs` vs `c`) drift and Room's KSP check will not catch a silently different
ordering.

### 5.3 `QuestionDao` additions

Same rule, but these two use the bare alias `categories` (not `c`) and the implicit join
`INNER JOIN categories ON questions.categoryId = categories.id`:

```kotlin
// countForPaper (Daos.kt:465-471) + the predicate.
@Query(
    "SELECT COUNT(*) FROM questions " +
        "INNER JOIN categories ON questions.categoryId = categories.id " +
        "WHERE categories.paperId = :paperId " +
        "AND questions.categoryId IN (:categoryIds)"
)
suspend fun countForCategories(paperId: String, categoryIds: List<String>): Int

// firstNewQuestionForPaper (Daos.kt:505-520) + the predicate, before ORDER BY.
suspend fun firstNewQuestionForCategories(paperId: String, categoryIds: List<String>): String?

// newQuestionsForPaper (Daos.kt:522-540) + the predicate, before ORDER BY.
suspend fun newQuestionsForCategories(paperId: String, categoryIds: List<String>): List<String>
```

`countForCategories` **needs** the `paperId` parameter that the current `countForPaper` has;
passing ids alone would let a stale id from another paper inflate the fresh count. (That is
the same class of leak §7.1.2 closes in the session order — belt and braces on the server
side too.)

Both new-question queries keep `ORDER BY categories.sortOrder, categories.title,
questions.sortOrder, questions.rowid`, and keep the
`LEFT JOIN card_state … AND card_state.paperId = categories.paperId` form. Keeping the join
matters: it is what makes the join *paper-scoped*, so a question edited in another paper can
never be treated as reset here.

### 5.4 Chunking (`app/src/main/java/com/mcqapp/data/local/ChunkedQueries.kt`)

The file already owns the one bind-variable budget: `MAX_BIND_VARS = 500` (`:13`) and
`List<T>.chunks()` (`:15-16`). **Reuse it, and promote both to `internal`.** Promotion is the
only option that works: §5.5's `bestDueReviewForCategoriesChunked` lives in `StudyStore.kt`
and calls `categoryIds.chunks()` across the package boundary, so keeping them private would
force a second copy of the constant and the helper.

Do not introduce a second limit: a second constant drifts, and the old larger figure bought
nothing. Each scoped statement binds the ids plus three scalars (`:paperId`, `:now`,
`:dayMs`), so a 500-id chunk binds 503 — comfortably under the 999-variable ceiling of
API 26-30 SQLite (`ChunkedQueries.kt:5-9`).

Add, all in `ChunkedQueries.kt`, delegating to §5.2/§5.3:

```kotlin
/** Sums all five StudyPopulation fields across chunks (§5.5). */
internal suspend fun CardStateDao.countsForCategoriesChunked(
    paperId: String, categoryIds: List<String>, now: Long
): StudyPopulation

/** Union of stale rows across chunks. */
internal suspend fun CardStateDao.staleForCategoriesChunked(
    paperId: String, categoryIds: List<String>
): List<QuestionIdHash>

/** Concatenates chunks in serving order (§5.4). */
internal suspend fun CardStateDao.dueForCategoriesChunked(
    paperId: String, categoryIds: List<String>, now: Long
): List<CardStateEntity>

/** First row of the first non-empty chunk. */
internal suspend fun CardStateDao.firstDueLearningForCategoriesChunked(
    paperId: String, categoryIds: List<String>, now: Long
): CardStateEntity?

internal suspend fun QuestionDao.countForCategoriesChunked(
    paperId: String, categoryIds: List<String>
): Int

internal suspend fun QuestionDao.firstNewQuestionForCategoriesChunked(
    paperId: String, categoryIds: List<String>
): String?

internal suspend fun QuestionDao.newQuestionsForCategoriesChunked(
    paperId: String, categoryIds: List<String>
): List<String>
```

`bestDueReviewForCategoriesChunked` is **not** here. Its global pick needs the
`CardStateEntity → CardState` mapping, which is private at `StudyStore.kt:44` (and duplicated
at `StatsStore.kt:307`), plus `Study.bestReview`. It lives in `StudyStore.kt` (§5.5).

**Aggregation.** `countsForCategoriesChunked` sums all five `StudyPopulation` fields —
`learning`, `due`, `leeches`, `newish`, `storedRows` (`Daos.kt:1222-1228`). `fresh` is
computed after the sum as `questions - (storedRows - newish)`; summing only "four pool
counts" would drop `storedRows` and double-count.

**Ordering: chunk-major, and that is the serving order.**

Every scoped query keeps the paper-wide `ORDER BY c.sortOrder, c.title, q.sortOrder,
q.rowid` verbatim, so **within one chunk** the rows are in the paper's authored order. A chunk
holds up to 500 categories — `c` is emphatically *not* fixed per chunk — but the ORDER BY does
not care: it is a flat sort, exactly as in the paper-wide query.

Chunks are consecutive slices of `CategorySelection.orderedIds` (pre-order of the tree).
Concatenating them gives:

- **A scope of at most 500 categories is one chunk, so it serves exactly the paper-wide order
  restricted to the scope.** This is the common case: a chapter, a parent and its children, a
  handful of leaves.
- **A larger scope** is the paper's order split at 500-category boundaries and concatenated.
  Deterministic and stable; it can differ from the restricted paper-wide order only where a
  boundary splits categories that interleave by `(sortOrder, title)`.

Do not claim *tree* order: pre-order (`A, A1, B`) is not the flat authored order
(`A, B, A1`) for a nested paper, and `CategoryNode` does not even carry `sortOrder`
(`Models.kt:291-300`). Tests assert **set** parity for nested papers and **order** parity for
flat papers and for single-chunk scopes (§9.3).

Consequences for the helpers:

- `dueForCategoriesChunked` / `newQuestionsForCategoriesChunked`: concatenate in chunk order.
  For a single-chunk scope this is the paper-wide order filtered; do not claim global parity
  beyond §9.3's cases.
- `firstDueLearningForCategoriesChunked` / `firstNewQuestionForCategoriesChunked`: the first
  non-empty chunk's first row is the first row of the serving order — exact by definition of
  that order, not an approximation.
- `bestDueReviewForCategoriesChunked`: order-independent. The global winner is necessarily the
  winner inside its own chunk, so it is among the per-chunk winners; `Study.bestReview` then
  applies the same comparator globally. Exact except an exact tie on `(retention, dueAt)`
  across a chunk boundary: the SQL would fall through to `(sortOrder, title, q.sortOrder,
  rowid)`, while `bestReview` keeps the supplied order. Paper-wide parity is unaffected (one
  query, one order); a cross-chunk tie may serve the other card. Do not claim exact parity.

**Proving the limit.** A Robolectric test with 901 ids does **not** prove this: Robolectric
SDK 34 runs a modern SQLite whose default bind limit is 32766, so an unchunked 901-id query
succeeds there. The proof is structural, in a new pure-JVM `ChunkShapeTest`: `chunks()` is
`internal`, so it asserts 1001 ids → `[500, 500, 1]`, 500 ids → one chunk, and an empty list
→ no chunk (§9.3). `ChunkedQueriesTest` keeps its existing concatenation oracle (1200 ids
return every row) as the CI completeness check. Record the same note in both KDocs.

### 5.5 `StudyStore` (`app/src/main/java/com/mcqapp/data/repository/StudyStore.kt`)

Add a scope parameter to the three read paths that serve a session. `getStudyCounts` does
**not** gain one (L1): the badge is paper-wide by D5, and its only caller with categories was
this plan's own test.

```kotlin
suspend fun getStudyQueue(
    paperId: String,
    now: Long = System.currentTimeMillis(),
    leechesOnly: Boolean = false,
    categoryIds: List<String> = emptyList()          // empty = whole paper
): List<StudyCard>

suspend fun getNextStudyCard(
    paperId: String,
    reviewsSinceNew: Int = 0,
    now: Long = System.currentTimeMillis(),
    categoryIds: List<String> = emptyList()
): StudyCard?

suspend fun getStudyEligibility(
    paperId: String,
    now: Long = System.currentTimeMillis(),
    introduceNewCards: Boolean? = null,
    categoryIds: List<String> = emptyList()
): StudyEligibility
```

Internal branches (each private helper takes `categoryIds` and dispatches):

| Helper | Whole paper | Scoped |
|---|---|---|
| `dueCards` | `dueForPaper` | `dueForCategoriesChunked` |
| `firstNewCard` | `firstNewQuestionForPaper` | `firstNewQuestionForCategoriesChunked` |
| `counts` | `countForPaper` + `countsForPaper` | `countForCategoriesChunked` + `countsForCategoriesChunked` |
| pull learning | `firstDueLearningForPaper` | `firstDueLearningForCategoriesChunked` |
| pull review | `bestDueReviewForPaper` | `bestDueReviewForCategoriesChunked` (defined here) |

`resetEdited` stays **paper-wide**. It is bounded by how many questions were edited, not by
the paper's size (`StudyStore.kt:208-225`, and the KDoc at `:197-207` says exactly that), so a
scoped branch would add a dispatch for no measurable gain and would delay the reset of an
edited question outside the current scope.

`bestDueReviewForCategoriesChunked` lives here because it needs the private
`CardStateEntity → CardState` mapping (`:44`) and `Study.bestReview` (§4.3, Phase 3). It takes
the **scheduler the pull already read** rather than reading settings again: `getNextStudyCard`
resolves one config snapshot per pull and both the learning and review branches must rank
with it, so a second `schedulerConfigNow()` inside the helper could straddle a settings change
and rank one pull with two configs. Private helper, behaviour otherwise identical.

```kotlin
private suspend fun bestDueReviewForCategoriesChunked(
    paperId: String,
    categoryIds: List<String>,
    now: Long,
    scheduler: com.mcqapp.domain.FsrsScheduler
): CardStateEntity? {
    val dayMs = com.mcqapp.domain.FsrsScheduler.DAY_MS.toDouble()
    val winners = categoryIds.chunks()                 // the same 500 primitive
        .mapNotNull { chunk -> bestDueReviewForCategories(paperId, chunk, now, dayMs) }
    if (winners.isEmpty()) return null
    val cards = winners.map { row ->
        val state = row.toDomain()                     // the private mapping at :44
        com.mcqapp.domain.StudyCard(row.questionId, reviewReason(scheduler, state, now), state)
    }
    val best = com.mcqapp.domain.Study.bestReview(scheduler, cards, now) ?: return null
    return winners.first { it.questionId == best.questionId }
}
```

`fresh` arithmetic in `counts` is unchanged — `questions - (storedRows - newish)` — so
summing the three inputs across chunks yields the same expression.

`recordStudyReview` is **unchanged**: schedules are keyed `(paperId, questionId)` and a
scoped session must not reshape storage.

### 5.6 `McqRepository` facade (`McqRepository.kt:653-677`)

Forward the parameter. Signatures keep `categoryIds` last with an empty default:

```kotlin
suspend fun getStudyQueue(paperId, now, leechesOnly, categoryIds = emptyList())
suspend fun getNextStudyCard(paperId, reviewsSinceNew, now, categoryIds = emptyList())
suspend fun getStudyEligibility(paperId, now, introduceNewCards, categoryIds = emptyList())
```

`getStudyCounts` keeps its current signature. No new repository method is required;
`getPaper(paperId)` already returns `Paper.categories`, which both destinations use to expand
the route (§6.4).

---

## 6. Navigation

### 6.1 D9 — the route carries ticked ids, not the expanded scope

Two measurements decide it:

- **The header JSON is rewritten on every answer.** `TestProgressStore.save` calls
  `settings.saveTestProgress(snapshot.header().toJson())` unconditionally
  (`TestProgressStore.kt:75-79`), so an expanded scope costs one id per category on every tap
  and every flag. A 1000-category scope is roughly 17–21 KB of JSON per write.
- **`TestSnapshot.matches` compares ids as sets** (`TestSnapshot.kt:47-48`). A stored expanded
  set stops matching the moment a category is added to the paper, and the resume offer
  silently disappears.

Both destinations already load the paper before they need the order — `TestViewModel.kt:199`,
`StudyViewModel.kt:165` — so expanding there is free.

The route therefore carries `CategorySelection.tickedIds(...)`: one entry per branch the
learner actually ticked, already in pre-order. Re-expanding it with `orderedIds` reproduces
the scope exactly (§4.1). Empty means whole paper, as today.

**Pre-upgrade snapshots.** Old snapshots already hold the ids that were ticked (the old drawer
did not expand), so a snapshot for "one parent" (`{P}`) still matches the new route for the
same parent (`{P}`). It matches, but the sitting does **not** continue unchanged: the route
now expands to P's whole subtree, so the rebuilt order is the paper-wide order restricted to
that subtree rather than the ids the sitting was taken over, and the stored `currentIndex`
(`TestSnapshot.kt:33`) can land on a different question. Say "the sitting is offered again",
not "the sitting resumes where it left off". The case that stops matching entirely is a
snapshot that stored a parent *and* one of its descendants (`{P, c1}`): that state is not
reachable from the new tree, the sets differ, and the sitting is not offered for resume.
Acceptable for an update; note both in the PR.

### 6.2 `Routes.kt`

```kotlin
fun studyRoute(
    paperId: String,
    categoryIds: List<String> = emptyList(),
    leechesOnly: Boolean = false
): String {
    val query = buildList {
        if (categoryIds.isNotEmpty()) add("categories=${encode(categoryIds.joinToString(","))}")
        if (leechesOnly) add("leechesOnly=true")
    }
    return "study/${encode(paperId)}" + if (query.isEmpty()) "" else "?" + query.joinToString("&")
}
```

Emitting the query only when non-empty preserves both existing assertions byte-for-byte —
`RoutesTest.kt:52` (`"study/paper-1"`) and `:60` (`"study/a%2Fb%3Fc%23d"`) — so `RoutesTest`
only gains cases.

`categoryIds` here are the **ticked** ids (§6.1), not the served set.

### 6.3 `McqNavHost.kt:151`

```kotlin
composable(
    route = "study/{paperId}?categories={categories}&leechesOnly={leechesOnly}",
    arguments = listOf(
        navArgument("paperId") { type = NavType.StringType },
        navArgument("categories") { type = NavType.StringType; defaultValue = "" },
        navArgument("leechesOnly") { type = NavType.BoolType; defaultValue = false }
    )
) { entry ->
    val categories = entry.arguments?.getString("categories").orEmpty()
        .split(",").filter { it.isNotBlank() }
    StudyScreen(
        repository = repository,
        paperId = paperId,
        categoryIds = categories,
        leechesOnly = leechesOnly,
        navController = navController
    )
}
```

Mirrors the test composable (`McqNavHost.kt:47-73`) exactly, including the split/filter.

The pattern change itself has a consequence for an old saved back stack; see §8.9.

### 6.4 Destination expansion

Both destinations expand once, from the paper they already loaded:

```kotlin
// StudyViewModel.load, after `val paper = repository.getPaper(paperId)`
scopeIds = if (categoryIds.isEmpty()) emptyList()
    else CategorySelection.orderedIds(paper?.categories.orEmpty(), categoryIds)

// TestViewModel.load, after `val paper = repository.getPaper(paperId)` (:199)
val scopeIds = if (categoryIds.isEmpty()) emptyList()
    else CategorySelection.orderedIds(paper?.categories.orEmpty(), categoryIds)
```

`scopeIds` is what goes to the queries: `getNextStudyCard`, `getStudyEligibility`,
`getStudyQueue`, `getSessionOrderForCategories(paperId, scopeIds)`, and
`countUngradedForCategories(scopeIds)` (`TestViewModel.kt:264`, already chunked at the
repository). The snapshot keeps the **route** `categoryIds` (ticked), because that is what
`matches` compares — `document.matches(paperId, categoryIds)` (`:733`) and the saved header
(`:838`) must **not** be switched to `scopeIds`, or every resume compares an expanded set
against a ticked route and never matches.

**This step is load-bearing, not tidiness.** The store has no category tree: it filters
`questions.categoryId IN (:categoryIds)`. A ticked parent id matches no question, because no
question sits directly in a parent, so passing raw route ids to Test mode serves **zero
cards** for a parent selection. Study mode already expands in `load()`; Test mode must too.

---

## 7. UI

### 7.1 `LibraryDrawer.kt`

Current signature (`LibraryDrawer.kt:51-61`) gains one callback and the tree becomes
tri-state.

```kotlin
internal fun DrawerContent(
    …
    onStartTest: (paperId: String, categoryIds: List<String>) -> Unit,
    onImport: () -> Unit,
    onStartStudy: (paperId: String, categoryIds: List<String>) -> Unit
)
```

### 7.1.1 A pre-existing bug this work must fix

`checkedCategories` is a single drawer-wide `Set<String>` (`LibraryDrawer.kt:63`) that is
never cleared when a different paper is expanded:

```kotlin
val checkedCategories = remember { mutableStateOf(setOf<String>()) }
```

Tick two categories in paper A, collapse A, expand B, tick one: the button under B reads
*"Start test (3 categories)"* and passes **paper B's id plus paper A's category ids**
(`:207`, `:213`).

The live vector is the session order, not `getRefsByCategories`: `getOrderForCategories`
(`QuestionStore.kt:117-119`) looks the ids up with `CategoryDao.getByIds`, then `loadOrder`
(`:139`) streams each category with `pageSessionRefsByCategory`, whose SQL filters on
`categoryId` only (`Daos.kt:424-429`). Nothing between the drawer and the questions checks
that the ids belong to `paperId`, so a cross-paper test genuinely serves another paper's
questions.

The selection must therefore become **per paper**, not per drawer:

```kotlin
// paperId -> the nodes that are on in that paper's tree
val onByPaper = remember { mutableStateOf<Map<String, Set<String>>>(emptyMap()) }

fun onFor(paperId: String): Set<String> = onByPaper.value[paperId].orEmpty()

fun toggle(paper: Paper, id: String) {
    val next = CategorySelection.toggle(paper.categories, onFor(paper.id), id)
    onByPaper.value = onByPaper.value + (paper.id to next)
}
```

`expandedPaperId` no longer needs to reset anything: each paper carries its own selection,
which is also nicer UX (collapsing a paper no longer loses the ticks). Do **not** clear the
map on collapse.

```kotlin
val on = onFor(paper.id)
val states = remember(paper.categories, on) {
    CategorySelection.states(paper.categories, on)
}
```

`CategoryTree` becomes:

```kotlin
@Composable
private fun CategoryTree(
    nodes: List<CategoryNode>,
    states: Map<String, CategoryCheckState>,
    onToggle: (String) -> Unit
) {
    for (node in nodes) {
        Row(...) {
            TriStateCheckbox(
                state = when (states[node.id]) {
                    CategoryCheckState.CHECKED -> ToggleableState.On
                    CategoryCheckState.UNCHECKED -> ToggleableState.Off
                    CategoryCheckState.INDETERMINATE -> ToggleableState.Indeterminate
                    null -> ToggleableState.Off
                },
                onClick = { onToggle(node.id) }
            )
            Text(node.title + if (node.totalQuestionCount > 0) " (${node.totalQuestionCount})" else "")
        }
        Column(Modifier.padding(start = 24.dp)) {
            CategoryTree(node.children, states, onToggle)
        }
    }
}
```

`states` is computed **once** per (paper, selection) by the caller, not per node — a per-node
`state(...)` call would be O(n²) on a 1000-node tree.

Buttons, replacing `LibraryDrawer.kt:208-226`:

```kotlin
val ticked = CategorySelection.tickedIds(paper.categories, on)
if (ticked.isNotEmpty()) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { onStartStudy(paper.id, ticked) }) {
            Icon(Icons.Default.PlayArrow, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.study_n_categories, ticked.size))
        }
        OutlinedButton(onClick = { onStartTest(paper.id, ticked) }) {
            Text(stringResource(R.string.test_n_categories, ticked.size))
        }
    }
}
```

The label counts **ticked branches**, not the expanded ids. Ticking one parent with 20
descendants must read "1 category", because that is the one box the learner ticked; an
expanded count would say 21 and would change as the tree grows. The exact number of served
questions is not known without a query, and the badge stays paper-wide anyway (D5).

`ticked` is `tickedIds`, not the old `checkedCategories.value.toList()`. Its order is
pre-order and deterministic, which is what makes chunk boundaries stable; the old `HashSet`
order was harmless for Test (which re-sorts) but not for the scoped Study path (§5.4).

Replace the hard-coded `"Start test (${selected.size} categories)"` at `:222` with the
resource above. The reason is i18n: this is a user-visible label in a screen that is already
fully externalised, and the new Study button needs the sibling string anyway.

`onToggle` becomes:

```kotlin
onToggle = { id -> toggle(paper, id) }
```

### 7.1.2 Hardening of Test

§7.1.1 fixes the caller, which is where the leak is. Two cheap defences remain, in the same
commit that changes Test semantics.

1. **Owned by Phase 3 (repository layer):** change `getOrderForCategories` to
   `(paperId: String, categoryIds: List<String>)`, look the categories up with
   `getByIdsChunked` (H1) **and** keep only the category rows that belong to that paper:
   `filter { it.paperId == paperId }`. The filter is on the **category rows**, not on the refs
   — `QuestionRef` carries no paper field — and the two are equivalent because `loadOrder`
   pages `pageSessionRefsByCategory` once per surviving category, so a dropped row contributes
   no refs. The parameter is
   **required, not defaulted** — a default would let the next caller forget it silently, which
   is exactly the failure this closes, and it is why §9.8 splits "updated" from "untouched".
   The definition and its three callers move together, all listed here so the phase is
   self-contained:

   | Site | Change |
   |---|---|
   | `QuestionStore.kt:117` | the definition: take `paperId`, replace `getByIds` with `getByIdsChunked`, keep only `it.paperId == paperId` category rows before paging |
   | `McqRepository.kt:466-467` | the facade: `getSessionOrderForCategories(paperId, categoryIds)` delegating to `getOrderForCategories(paperId, categoryIds)` |
   | `TestViewModel.kt:209` | the scoped branch: `repository.getSessionOrderForCategories(paperId, categoryIds)` (the ViewModel already holds `paperId`) |
   | `SessionOrderLoadTest.kt:93`, `:99` | the two test calls gain the seeded fixture's paper id; the assertions do not change |

   Those are all of them: `rg "getOrderForCategories|getSessionOrderForCategories"` over
   `app/src` returns exactly these five locations and nothing else.

2. Optionally add `AND categories.paperId = :paperId` to `getRefsByCategories` (`Daos.kt:399`).
   This is **defence in depth only**: its current caller already passes the paper's own ids.
   One parameter, no behaviour change today.

### 7.2 `LibraryScreen.kt`

Add the callback next to `onStartTest` (`LibraryScreen.kt:326`):

```kotlin
onStartStudy = { paperId, categoryIds ->
    scope.launch { drawerState.close() }
    Logger.i("LIB", "Drawer study: paperId=$paperId, categories=${categoryIds.size}")
    navController.navigate(
        com.mcqapp.ui.navigation.studyRoute(paperId, categoryIds)
    )
},
```

The paper card's `onStudy` (`:409`) is unchanged — it stays whole-paper.
`LibraryViewModel.studyCounts` stays paper-wide (D5).

### 7.3 `StudyViewModel.kt`

Constructor (`:121-125`):

```kotlin
class StudyViewModel(
    application: Application,
    private val paperId: String,
    private val categoryIds: List<String> = emptyList(),   // ticked ids from the route
    private val leechesOnly: Boolean = false
) : AndroidViewModel(application)
```

Changes:

1. `load()` (`:162`, paper at `:165`): resolve the paper once, expand the route into
   `scopeIds`, and record the scope as **typed state**. No Context and no copy here: a
   Robolectric unit test runs without app resources (C19), so the label is a value the screen
   renders:
   ```kotlin
   private var scopeIds: List<String> = emptyList()

   // in load(), after `val paper = repository.getPaper(paperId)`
   scopeIds = if (categoryIds.isEmpty()) emptyList()
       else CategorySelection.orderedIds(paper?.categories.orEmpty(), categoryIds)

   val label = when {
       categoryIds.isEmpty() -> ScopeLabel.WholePaper
       categoryIds.size == 1 -> ScopeLabel.One(
           CategorySelection.titleOf(paper?.categories.orEmpty(), categoryIds.first()).orEmpty()
       )
       else -> ScopeLabel.Many(categoryIds.size)
   }
   // then, in the existing `_state.update { it.copy(...) }` call that follows:
   _state.update { it.copy(scopeLabel = label, …) }
   ```
2. `nextCard()` (`:209`): `repository.getNextStudyCard(paperId, reviewsSinceNew, categoryIds = scopeIds)`.
3. `finishEmpty()` (`:261`): `getStudyEligibility(paperId, introduceNewCards = true, categoryIds = scopeIds)`.
4. The drill branch passes `scopeIds` so the tricky drill is scoped too (D4) — at **both**
   call sites, `load()` (`StudyViewModel.kt:200-204`) and `restart()` (`:711-716`). Missing
   `restart()` is the subtle one: it re-pulls the drill from the scheduler now, which is
   exactly what `studyAgainRepullsTheDrillFromTheSchedulerNow` pins, so a scoped tricky drill
   would stay scoped for the first pass and widen to the whole paper on "Study again".
   `NoDeadCodeTest` (`:60-82`) scans every production call of `getStudyQueue(` and fails
   unless `leechesOnly = true` appears within 200 characters, so each call keeps the named
   argument in the same window:
   ```kotlin
   if (leechesOnly) {
       drillCards = repository.getStudyQueue(paperId, leechesOnly = true, categoryIds = scopeIds)
   }
   ```
5. Add `scopeLabel: ScopeLabel = ScopeLabel.WholePaper` at the **end** of `StudyUiState`
   (`StudyViewModel.kt:46-95`), after `breakDismissedAt`, not next to `paperTitle`. Two
   adjacent `String` fields invite a later positional `copy(...)` to rebind the wrong one;
   putting it last makes every existing positional construction unaffected. Declare
   `EmptyReason` and `ScopeLabel` at top level in the same file, next to `StudyUiState`:

   ```kotlin
   enum class EmptyReason { None, NothingDue, NothingDueInScope, NothingTricky, NothingTrickyInScope }

   sealed interface ScopeLabel {
       data object WholePaper : ScopeLabel
       data class One(val title: String) : ScopeLabel
       data class Many(val count: Int) : ScopeLabel
   }
   ```

   `emptyReason` changes type in place, same position:
   `val emptyReason: EmptyReason = EmptyReason.None`. Every reset site that wrote `""`
   (`stop()`, `restart()`) writes `EmptyReason.None`.

### 7.4 `StudyScreen.kt`

Header (`:445`): under the paper title, render the scope. The copy is resolved **here**, not
in the ViewModel (C19):

```kotlin
Column(Modifier.weight(1f)) {
    Text(state.paperTitle, … testTag("study-paper-title"))
    val scopeText = when (val label = state.scopeLabel) {
        ScopeLabel.WholePaper -> ""
        is ScopeLabel.One -> label.title
        is ScopeLabel.Many -> stringResource(R.string.study_scope_many, label.count)
    }
    if (scopeText.isNotEmpty()) {
        Text(
            scopeText,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag("study-scope-label")
        )
    }
}
```

Empty state: add a scoped reason so "Nothing due right now." is not a lie about the whole
paper, again resolved in the screen. `finishEmpty()` only says **why**:

```kotlin
val emptyText = when (state.emptyReason) {
    EmptyReason.None -> ""
    EmptyReason.NothingDue -> stringResource(R.string.nothing_due)
    EmptyReason.NothingDueInScope -> stringResource(R.string.nothing_due_in_scope)
    EmptyReason.NothingTricky -> stringResource(R.string.nothing_tricky)
    EmptyReason.NothingTrickyInScope -> stringResource(R.string.nothing_tricky_in_scope)
}
```

with `emptyReason = when { it.reviewed > 0 -> EmptyReason.None; … }` and the same conditions
in the same order as before. The `!introduceNewCards && freshAvailable` offer keeps keying
off its own fields, not off the reason.

The two existing literals (`StudyViewModel.kt:290-294`) keep their exact text in
`strings.xml`. `anEmptySessionOffersToTurnNewCardsOnAndHonoursTheFlip` **is edited in this
phase** (C19): it asserted the resolved string, which the ViewModel can no longer produce, so
it now asserts `EmptyReason.NothingDue`. The copy lives on in `strings.xml` and is pinned by
the screen, not by that assertion.

Also pass `categoryIds` through the factory and the ViewModel key:

```kotlin
val viewModel: StudyViewModel = viewModel(
    key = "study-$paperId-${categoryIds.joinToString(",")}-$leechesOnly",
    factory = StudyViewModelFactory(context.applicationContext as Application, paperId, categoryIds, leechesOnly)
)
```

### 7.5 `ViewModelFactories.kt:58`

```kotlin
class StudyViewModelFactory(
    private val application: Application,
    private val paperId: String,
    private val categoryIds: List<String> = emptyList(),
    private val leechesOnly: Boolean = false
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        StudyViewModel(application, paperId, categoryIds, leechesOnly) as T
}
```

> There is exactly one construction site — `StudyScreen.kt:98-102` — and it passes
> `leechesOnly` **positionally**. Inserting `categoryIds` before it therefore requires
> updating that one call (`StudyScreen.kt:101`); nothing else constructs the factory
> (`grep -rn "StudyViewModelFactory("` returns only the class and this site).

### 7.6 `strings.xml`

```xml
<string name="study_n_categories">Study (%1$d categories)</string>
<string name="test_n_categories">Test (%1$d categories)</string>
<string name="study_scope_many">%1$d categories</string>
<string name="nothing_due">Nothing due right now.</string>
<string name="nothing_due_in_scope">Nothing due in the selected categories.</string>
<string name="nothing_tricky">No tricky questions right now.</string>
<string name="nothing_tricky_in_scope">No tricky questions in the selected categories.</string>
```

Phase 4 adds the five it uses (`study_scope_many`, `nothing_due`, `nothing_due_in_scope`,
`nothing_tricky`, `nothing_tricky_in_scope`); Phase 5 adds the two button labels. Each phase
then compiles and passes on its own (§11).

---

## 8. Edge cases and invariants

### 8.1 Invariants to preserve

- **Badge/pull agreement.** `getStudyEligibility` and `getNextStudyCard` must read the same
  scoped predicate and the same chunk helpers. `getStudyCounts` stays paper-wide and
  unchanged (D5, L1).
- **No schedule writes for unseen questions.** `resetEdited` stays paper-wide and bounded by
  *edited* rows; it is not scoped (N7).
- **Schedules are keyed `(paperId, questionId)`** — a scoped session must not move or
  duplicate a card.
- **Study never writes attempts.** Unchanged: `recordStudyReview` only touches
  `card_state` + `review_log`.
- **A selection belongs to exactly one paper.** The drawer keeps `paperId -> on`; a
  Study/Test route must only ever receive ids from its own paper's tree (§7.1.1).
- **The route carries ticked ids only.** The destination expands from `Paper.categories`
  (D9, §6.4); a caller that passes served ids would still work, because `orderedIds` is a
  union of subtrees, but the contract is "ticked".

### 8.2 Category deleted mid-session
The next query returns nothing for those ids → `pullCard` sees null → `finishEmpty`. No
special handling; `nextCard()` (`StudyViewModel.kt:209`) already tolerates a vanished
question. A stale id in the route is inert: `orderedIds` ignores ids that are not in the
loaded tree.

### 8.3 Question moved out of scope mid-session
It simply stops appearing (the query filters by its *current* `categoryId`). No stale
schedule is written; its row stays for a later paper-wide session.

### 8.4 Selecting the whole tree
`orderedIds` yields every category, so counts and the *set* of served cards must equal the
paper-wide path. Ordering is equal when the whole scope fits one chunk (≤500 categories,
which includes every flat paper of that size); a larger scope is chunk-major (§5.4). Pin
both: row-set equality always, row-for-row equality for a single-chunk scope (§9.3).

### 8.5 Empty selection
The Study button is not rendered. A deep link with `categories=` (blank) parses to an empty
list → whole paper, which is the safe interpretation.

### 8.6 Leeches + scope
`getStudyQueue(…, leechesOnly = true, categoryIds)` filters the scoped due pools via
`Study.onlyLeeches` (`SpacedRepetition.kt:490`), so it intersects correctly.

### 8.7 Known limitation: commas in category ids
`testRoute` today does `encode(categoryIds.joinToString(","))`. Navigation decodes the query
value, so an id containing a comma is indistinguishable from the separator. This is
pre-existing (`RoutesTest.kt:64-71` only covers ids *without* commas). Study inherits it by
mirroring the convention.

Optional hardening (recommended as a separate change so both routes move together): encode
each id individually and join with a separator that cannot appear in an id, e.g. `US` (0x1F),
then split on it after decoding. Or JSON-encode the list. Either way, change `testRoute` and
`studyRoute` in the same commit and add a `RoutesTest` case with `"c,1"`.

### 8.8 Chunk boundary at 500
An id list of exactly 500/501 must produce identical results. Covered by the pure-JVM chunk
test and by the 501-category set test (§9.3). There is no larger boundary to reason about.

### 8.9 Changing the Study route pattern

Adding `?categories=` changes the destination's route string, and Navigation identifies
destinations by route. A back stack restored by the system after an app update (process
death across versions) still names the old pattern; depending on the Navigation version the
restore throws or falls back to the start destination, so an in-flight session is lost
rather than resumed. Acceptable for a one-off update — the alternative is to keep the old
pattern and hand the selection over through a `SavedStateHandle`. Record it in the PR.

---

## 9. Tests

Naming follows the repo's convention: one behaviour per test, descriptive sentence names.

### 9.1 New pure-JVM test: `CategorySelectionTest.kt`

Runs on this host (no Robolectric).

| Test | Asserts |
|---|---|
| `tickingAParentTicksEveryDescendant` | toggle on a root → every descendant is on and `state(root) == CHECKED` |
| `untickingAParentClearsTheWholeSubtree` | toggle twice → `on` is empty again |
| `untickingOneChildMakesTheParentIndeterminate` | tick parent, untick child → `state(parent) == INDETERMINATE` and the parent is not in `orderedIds` |
| `tickingTheLastMissingChildMakesTheParentChecked` | INDETERMINATE → CHECKED after the missing child is ticked |
| `tickingEveryLeafChecksTheAncestors` | leaves ticked one by one → the root is CHECKED and equals a single tick (AC 10) |
| `tickingAnIndeterminateParentChecksEverything` | INDETERMINATE + toggle → CHECKED, whole subtree on |
| `anIndeterminateBranchContributesOnlyItsOnChildren` | `orderedIds` excludes the branch and includes the fully-on child |
| `toggleIgnoresAnIdThatIsNotInTheTree` | unknown id → `on` unchanged |
| `orderedIdsFollowsPreOrder` | hand-written pre-order (not `sortOrder`: `CategoryNode` has none) |
| `aForestWithSeveralRootsExpandsIndependently` | no cross-root leakage |
| `deepNestingIsFullyExpanded` | 5-level chain |
| `stateOfAnEmptyPaperIsUnchecked` | empty forest |
| `statesDerivesFromTheStoredOnSet` | every node's state matches its membership in `on` |
| `reExpandingTheTickedIdsReproducesTheScope` | `orderedIds(tickedIds(on)).toSet() == on` for a nested fixture, a multi-root forest and a partially-selected tree — the D9 round trip (§4.1, §6.1) |
| `statesOnATenThousandNodeForestIsCorrect` | deterministic correctness on a 10k-node **wide** forest; no wall clock, no timeout |

The revision-1 tests `expandIgnoresUnknownIds`, `statesAgreesWithExpandForEveryNode` and
`statesIsLinearInTheNodeCount` disappear with `expand` and the timing assertion; the table
above replaces them.

### 9.2 New pure-JVM test: `StudyScopeLabelTest.kt` (optional)
`titleOf` resolution for 1 vs N ticked branches, and the "N categories" formatting decision.

### 9.3 New Robolectric test: `StudyCategoryScopeTest.kt`

**CI-only** (x86_64) — record that in the class KDoc, matching `StudyPullQueryCountTest.kt:26`.
Added in Phase 3, because it asserts the signatures that phase introduces.

| Test | Asserts |
|---|---|
| `aScopedEligibilityOnlyCountsTheSelectedCategory` | `getStudyEligibility(p, categoryIds = …)` on a seeded 2-category paper |
| `aScopedEligibilityIncludesDescendants` | a scope expanded from a parent id counts its children — **and the parent id alone yields 0 fresh**, so the expansion is proved load-bearing rather than assumed. The store is flat (`questions.categoryId IN (:categoryIds)`), so `[cP]` matches nothing by itself; the test expands with `CategorySelection.orderedIds` first and asserts 3. |
| `selectingEveryCategoryMatchesTheWholePaper` | scoped eligibility == paper-wide for `due/learning/leeches/fresh` |
| `aFlatPaperServesTheSameRowsInTheSameOrder` | row-for-row parity on a flat paper (§8.4) |
| `aScopedPaperServesThePaperWideOrderRestrictedToTheScope` | one-chunk scope: order == paper-wide order filtered |
| `aNestedPaperServesTheSameRows` | **set** equality only (N1: chunk-major order is not tree order) |
| `crossingAChunkBoundaryKeepsTheServedSet` | 501-category scope: same set as the unchunked expectation |
| `theScopedPullNeverServesOutsideTheScope` | 20 pulls, all ids in scope |
| `theScopedPullServesTheMostForgettableCardFirst` | matches `Study.bestReview`; exact ties are not asserted |
| `theScopedNewCardIsTheFirstInServingOrder` | first row of the first non-empty chunk |
| `aScopedLeechDrillIntersectsWithTheCategories` | leeches only, and only in scope |

**Chunk-shape proof: a new pure-JVM class, `ChunkShapeTest.kt`**
(`app/src/test/java/com/mcqapp/ChunkShapeTest.kt`, no Robolectric import, so
`run-jvm-tests.sh` picks it up): assert 1001 ids → `[500, 500, 1]`, 500 ids → one chunk, and
an empty list → no chunk. It must **not** go into `ChunkedQueriesTest`, which is
`@RunWith(RobolectricTestRunner::class)` (`:39`) and therefore excluded from the local run —
a green Phase 2 that skipped the shape proof would be a false green. Keep the existing
1200-id concatenation oracle in `ChunkedQueriesTest` as the CI completeness check.

A 901-id Robolectric test would not prove the limit either: Robolectric SDK 34's SQLite allows
32766 variables, so an unchunked 901-id query passes there (N4).

Counting the local run: `run-jvm-tests.sh` prints 118 class files without Robolectric but
produces 116 TEST xml files, because two of those files declare no `@Test` and are support
code (`ExportTestSupport.kt`, `PagingWalk.kt`). That two-file gap is expected, not a silently
skipped test.

**Query-count bound (extend `StudyPullQueryCountTest.kt`).** A scoped pull costs at most
`1 + 3 * ceil(scopeCategories / 500)` Room queries — the paper-wide `staleForPaper` read plus
one learning plus `ceil` review-head plus `ceil` new-card queries, the last two short-circuiting
at the first chunk that answers. It must not grow with the question count, nor with categories
beyond that ceiling; a 501-category scope is therefore at most 7, and the recorded value is
expected in 4..7 depending on which chunk holds the served rows. The same test proves the pull
never reads the answer key. The test is Robolectric, so CI is where the count is observed.

### 9.4 Extend `StudyViewModelPullTest.kt` (Robolectric)

| Test | Asserts |
|---|---|
| `aCategorySessionStopsWhenItsScopeIsDry` | empty state reached while another category still has cards |
| `aCategorySessionLeavesOtherCategoriesSchedulesAlone` | other cards' `dueAt` unchanged |
| `theScopeLabelNamesASingleCategory` | `state.scopeLabel == ScopeLabel.One("Cat")` |
| `theScopeLabelCountsSeveralCategories` | `state.scopeLabel == ScopeLabel.Many(2)` |
| `resumingWithAScopeStillPullsOnlyThatScope` | new ViewModel instance, same ids |

`anEmptySessionOffersToTurnNewCardsOnAndHonoursTheFlip` (`:167`) asserts
`EmptyReason.NothingDue`, not the resolved copy: the ViewModel can no longer resolve strings
(C19), and the string itself is unchanged in `strings.xml`. The label cases assert typed
values for the same reason — the screen owns `stringResource`.

### 9.5 Extend `RoutesTest.kt`

```kotlin
@Test fun aWholePaperStudyRouteIsUnchanged()      // "study/paper-1"
@Test fun studyCarriesCategories()                // categories=c1%2Cc2
@Test fun studyCarriesLeeches()                   // leechesOnly=true, without categories
@Test fun aScopedStudyRouteEncodesThePaperSegment()
```

`RoutesTest` is `@RunWith(RobolectricTestRunner)` because it calls `android.net.Uri.encode`,
so it is **not** pure JVM: it compiles locally and runs on CI. On this aarch64 host every
Robolectric class aborts — `RoutesTest` exits 134 (SIGABRT) and writes an xml reading
`tests="1" skipped="1" failures="0"`, which is an abort, not a pass. Expected count on CI:
11 tests (7 existing + the 4 above).

### 9.6 Structural test: extend `StudyActionStructureTest.kt`

`StudyActionStructureTest` is plain JUnit that reads source text (`StudyActionStructureTest.kt:13`,
no `RunWith`). It can assert **source shape only** — it cannot tick a checkbox. The behavioural
version of the per-paper rule lives in §9.7 instead.

| Test | Asserts (source shape only) |
|---|---|
| `theDrawerOffersStudyBesideTest` | both callbacks are invoked from the selected-categories block |
| `theCategoryTreeUsesATriStateCheckbox` | `TriStateCheckbox` source present |
| `theDrawerHasNoHardCodedActionLabels` | button labels come from `stringResource` |
| `theDrawerKeepsASelectionPerPaper` | the `Map<String, Set<String>>` is declared at `DrawerContent` scope and read as `onByPaper.value[paper.id]`, **not** created inside the per-paper `item` lambda. This proves where the state lives; it does not prove two papers stay isolated — §9.7 does. |
| `theDrawerLabelsTheTickedBranchCount` | the label uses `tickedIds(...)`, not the expanded scope (§7.1) |

### 9.7 On-device: extend `app/src/androidTest/.../StudyAndSelectionUiTest.kt`

| Test | Asserts |
|---|---|
| `tickingAParentCategoryTicksItsChildren` | child checkbox is On |
| `untickingOneChildShowsTheParentIndeterminate` | parent renders Indeterminate |
| `tickingInTwoPapersKeepsTheirSelectionsApart` | tick in A, collapse, expand B, tick in B → B's button counts 1; start B's Test and assert no A question appears (§7.1.1). This is the behavioural half that §9.6 cannot make. |
| `studyFromTheDrawerServesOnlyTheSelectedCategory` | card stems belong to that subtree |

### 9.8 Tests this change updates

| Test | Update |
|---|---|
| `SessionOrderLoadTest.kt:93`, `:99` | the two `getSessionOrderForCategories(...)` calls gain the fixture's paper id. **Updated, not untouched** (§7.1.2): the new parameter is required, so the file does not compile as-is. The assertions stay the same (`q-c1, q-a1, q-a2`, and empty for an unknown id). |
| `SessionOrderLoadTest.aCategoryIdFromAnotherPaperIsDropped` | a `p2` category id under `p1`'s order yields empty. Without the filter, `cA` resolves and `loadOrder` serves `q-a1`/`q-a2` under `p2` — this is the H1 regression pin. |
| `SessionOrderLoadTest.aMixedSelectionKeepsOnlyThisPapersIds` | `p1` with one `p2` id keeps exactly `q-c1, q-a1, q-a2`. |
| fixture | `paper p2`, category `cZ`, question `q-z1` added to the seeded fixture the two cases above use. |
| `TestCategoryScopeTest.aTickedParentServesItsChildQuestions` (Robolectric) | constructs a `TestViewModel` with a **ticked parent id** and loads; the session contains both children's questions, and `countUngradedForCategories` for that scope counts exactly 1. A repository-level test cannot see the wiring, so this must construct the ViewModel — that is exactly why the missing Test-mode expansion survived the first Phase 4 verification (C17, C18). |
| `TestCategoryScopeTest.aTickedChildStillServesItsOwnQuestions` | ticking `cB` serves `q2` and counts its 1 ungraded question. The fixture's `cB` question has **no answer key** (`saveQuestion` accepts an empty key set; imports land the same way), because a graded question makes "ungraded in scope" and "scope lost the filter" both read 0 |
| `RoutesTest.kt:52`, `:60` | pass byte-for-byte; the scoped cases are added (§9.5) |
| `StudyViewModelPullTest.kt` | scoped cases added (§9.4); `:167` now asserts `EmptyReason.NothingDue` (C19) |

`SessionOrderLoadTest` and `TestCategoryScopeTest` are Robolectric, so every case in this table
**compiles locally and executes on CI x86_64**; no local green claims them.

### 9.9 Tests that must keep passing untouched

`StudyQueueTest`, `DrillTest`, `StudySessionStateTest`, `StudyCountsInputTest`,
`StatsAggregatorTest`, `HistoryStatsParityTest`, `NoDeadCodeTest`. None of them calls
`getOrderForCategories`. They stay compiling because the **store** methods (`getStudyQueue`,
`getNextStudyCard`, `getStudyEligibility`) keep a defaulted `categoryIds`, and because the
drill call keeps `leechesOnly = true` inside the 200-character window. `NoDeadCodeTest` is
untouched and runs in the local pass now that the runner selects on the import (C8, §9.10).

That is the distinction the previous wording blurred: a defaulted *store* parameter is
backwards compatible, but a **new required** parameter on `getOrderForCategories` is not, so
its four call sites are owned by Phase 3 (§7.1.2) rather than left to defaults.

### 9.10 The local runner is not the pure-JVM subset

> **Updated (C8 applied):** the script now selects on `^import org.robolectric`, so the five
> classes below run in a normal local pass — verified at `classes=118 tests=932 failures=0`.
> The analysis below records why the old selector was wrong; it is history, not the current
> behaviour.

`run-jvm-tests.sh:24-27` used to select classes like this (verbatim):

```sh
find "$TEST_SRC" -name '*.kt' | sed "s|^$TEST_SRC/||" | while read -r f; do
  grep -q 'Robolectric' "$TEST_SRC/$f" || \
    printf '%s\n' "$(printf '%s' "$f" | sed 's|/|.|g; s|\.kt$||')"
done | sort > "$LIST"
```

The pattern matched the word **anywhere in the file, comments included**. Five real test
classes mention it only in prose, so the old local run never executed them:

| Class | Where the word appears |
|---|---|
| `NoDeadCodeTest.kt` | a comment at `:247` ("its Robolectric wiring test") |
| `MistakesTest.kt` | KDoc at `:12` ("which needs Robolectric") |
| `BackupStreamingReadTest.kt` | KDoc at `:23` ("no database, no Robolectric") |
| `ScaledRowStorageTest.kt` | KDoc at `:27` ("Robolectric passes every image through") |
| `DownscaledImagesTraversalTest.kt` | KDoc at `:14` ("Robolectric passes every image through untouched") |

All five are pure JVM and pass when invoked directly: the audit invoked all five by name and
all five passed (6, 4 and 5 tests in the three behavioural classes, plus the two guard
classes). **`NoDeadCodeTest` is the guard this plan leans on for orphaned helpers (§7.3), and
it was not running in the local suite at all until the selector was fixed.**

Therefore: **"green on `run-jvm-tests.sh`" means "green on the pure-JVM subset, which is the
only subset this host can run"** — with the caveat that the script selects by the word, not by
the import, so what it selects is not exactly the pure-JVM set.

The selector is not wrong in its broad effect: it selects exactly the files that never
mention the runtime, which is coincidentally the only subset that can run here. Every file
that mentions Robolectric aborts on this aarch64 host — `RoutesTest` exits 134 (SIGABRT) and
writes `tests="1" skipped="1" failures="0"`, which is an abort, not a pass (§9.5, C15). The
defect is narrower and real: **prose can hide a pure-JVM class**, and it did, five times.
Phase greens must not claim the five skipped classes ran; when a phase touches one
(`NoDeadCodeTest` in Phases 4 and 5), invoke it by name:

```sh
sh gradlew :app:testDebugUnitTest --tests 'com.mcqapp.NoDeadCodeTest' --no-daemon
```

The durable fix is to select on the import instead of the word —
`grep -q '^import org.robolectric'` — so prose cannot hide a test class. That is a change to a
script outside the repo (`/sdcard/repo/apk-build`), so it is recorded as a recommended
follow-up in §12 and not made here (C8).

---

## 10. Documentation

- `README.md` §Screens — drawer row: "per-paper category trees (reorderable),
  **test- and study-by-category**".
- `README.md` §Spaced repetition — a short subsection: "**Scoped study.** Pick categories
  in the drawer; a selected parent includes its subcategories. Badges stay paper-wide; the
  scope only decides what the session serves. Schedules are still per question, so studying
  a subtree never changes another category's dates."
- `README.md` §Edge cases — note that ending a scope does not reset anything, that deleting a
  category mid-session ends the sitting, and that a scope is not resumable by design.
- `README.md` §Known limitations (or the drawer paragraph) — record that category selection
  is now per paper (previously ticks leaked across papers, §7.1.1).
- `AGENTS.md` §Build and test — **applied.** The fenced command block (`AGENTS.md:7-11`) and
  the `aapt2FromMavenOverride` bullet (`:13-14`) were the two that hard-coded the dead
  `/tmp/ncal-aapt2/aapt2`, and both were replaced: the block sources
  `/sdcard/repo/apk-build/env.sh` and passes `$MCQ_AAPT2_OVERRIDE`; the aapt2 bullet names
  the working `/opt/android-aapt2/aapt2` (exported as `MCQ_AAPT2` at `env.sh:12`, turned into
  `MCQ_AAPT2_OVERRIDE` at `:24`). A runner bullet was added; the Robolectric and
  no-new-dependency bullets (`:15-19`) are unchanged. `run-jvm-tests.sh` was patched to
  select classes on `^import org.robolectric` instead of the word, so the five it used to
  hide (`NoDeadCodeTest`, `MistakesTest`, `BackupStreamingReadTest`, `ScaledRowStorageTest`,
  `DownscaledImagesTraversalTest`) are covered by a normal local pass — verified at
  `classes=118 tests=932 failures=0`. A reintroduced word-match would show as a lower class
  count. Shipped text:

  ````markdown
  ## Build and test

  ```bash
  # Source the host environment first: it exports JAVA_HOME, ANDROID_HOME and
  # MCQ_AAPT2_OVERRIDE (the aapt2 the ARM64 host needs).
  . /sdcard/repo/apk-build/env.sh
  cd "$MCQ_REPO" && sh gradlew :app:assembleDebug :app:testDebugUnitTest \
    $MCQ_AAPT2_OVERRIDE --no-daemon
  ```

  - The `aapt2FromMavenOverride` is only needed on this ARM64 host; `env.sh` exports it
    (`MCQ_AAPT2`, default `/opt/android-aapt2/aapt2`). GitHub Actions uses the bundled `aapt2`
    and passes no override. The old `/tmp/ncal-aapt2/aapt2` path no longer exists.
  - `run-jvm-tests.sh` used to select classes by grepping the source for the **word**
    Robolectric, so a comment could hide a class: `NoDeadCodeTest`, `MistakesTest`,
    `BackupStreamingReadTest`, `ScaledRowStorageTest` and `DownscaledImagesTraversalTest` were
    silently skipped. It now selects on `^import org.robolectric`, so a full local pass covers
    them. If a future edit reintroduces a word-match, the symptom is a lower class count.
  ````

There is no deleted scale-notes document to update: `c4a8f07` removed it along with its
companion file. Scale targets in this plan are stated inline (§5.4, §12) so they no longer
depend on a removed file.

---

## 11. Implementation order

Each phase compiles and is independently reviewable. Every phase ends green.

**Phase 1 — pure domain (no behaviour change)**
1. Add `CategorySelection.kt`, lift `BrowseScreen.flattenCategories` into it, and turn
   `BrowseScreen.subtreeIdsOf` into a thin delegating wrapper (§4.1).
2. Add `CategorySelectionTest` (§9.1), including `reExpandingTheTickedIdsReproducesTheScope`:
   the D9 route round trip is a Phase 1 assertion, not a later one.
3. Green: `sh /sdcard/repo/apk-build/run-jvm-tests.sh` **and** `CategorySelectionTest` invoked
   by name. "Green on `run-jvm-tests.sh`" is the subset that script selects, not the pure-JVM
   subset (§9.10): do not claim the five keyword-skipped classes ran, and do not claim the
   dead-code guard did.

**Phase 2 — Room scoped queries**
4. Add the 8 DAO methods, each with the predicate on its `WHERE` line (§5.2, §5.3).
5. Add the 7 chunk helpers to `ChunkedQueries.kt`, reusing `MAX_BIND_VARS = 500` (§5.4).
6. Add `ChunkShapeTest.kt` — a **new pure-JVM class**, no Robolectric import — asserting
   1001 ids → `[500, 500, 1]` and 500 ids → one chunk (§9.3). Not `ChunkedQueriesTest`: that
   is Robolectric and the local run never executes it.
7. Green: `sh /sdcard/repo/apk-build/build-apk.sh` (Room KSP validates every annotation) and
   `run-jvm-tests.sh`, which now selects `ChunkShapeTest`. That is the selected subset, not
   the pure-JVM subset: the five classes in §9.10 are outside this green unless invoked by
   name.

**Phase 3 — repository + store**
8. Add `Study.bestReview` (§4.3) and `bestDueReviewForCategoriesChunked` in `StudyStore.kt`
   (§5.5).
9. Thread `categoryIds` through `StudyStore` and `McqRepository`; `getStudyCounts` is
   untouched (§5.6).
10. Change `getOrderForCategories` to `(paperId, categoryIds)` with `getByIdsChunked` and the
    paper filter, and update the definition and its three callers: the
    `McqRepository` facade (`:466-467`), `TestViewModel.kt:209`, and
    `SessionOrderLoadTest.kt:93`/`:99` (§7.1.2). This is the H1 edit, and this phase owns it.
11. Add `StudyCategoryScopeTest` here, because it asserts the signatures this phase adds
    (§9.3).
12. Green: compiles locally and the pure-JVM suite is green. `StudyCategoryScopeTest` is
    Robolectric, so it **compiles here and executes on CI x86_64** — CI is the authority for
    it, not the local run.

**Phase 4 — navigation + ViewModel**
13. `Routes.kt` (ticked ids) and `McqNavHost.kt` (§6.1-§6.3).
14. **Test mode expansion — load-bearing.** In `TestViewModel.load`, after the paper is loaded,
    expand `categoryIds` with `CategorySelection.orderedIds` into `scopeIds`; pass `scopeIds`
    to `getSessionOrderForCategories` (`TestViewModel.kt:209`) and
    `countUngradedForCategories` (`:264`); leave `document.matches(paperId, categoryIds)`
    (`:733`) and the saved header (`:838`) on the **ticked** ids, because the snapshot
    compares routes (§6.4). Without this step a ticked parent matches no question and the
    session serves zero cards.
15. `StudyViewModel` (both drill call sites, §7.3), `StudyViewModelFactory`, `StudyScreen`,
    and the five strings they use (§7.3-§7.6).
16. Extend `RoutesTest` (§9.5), `StudyViewModelPullTest` (§9.4), add the TestViewModel-level
    scope case (§9.8), and assert the scoped-pull ceiling of `1 + 3 * ceil(scope/500)` for a
    501-category scope (at most 7) in `StudyPullQueryCountTest`; CI records the observed value
    in Phase 6 — it cannot be measured on this host.
17. Green: compiles locally. `RoutesTest` (expected 11 tests: 7 old + 4 new),
    `StudyViewModelPullTest` and the new TestViewModel case are Robolectric, so they are
    **CI-authoritative**: no Robolectric class can be locally green on this host — `RoutesTest`
    aborts with SIGABRT (exit 134) and writes `tests="1" skipped="1" failures="0"`, which is an
    abort, not a pass. Invoke `NoDeadCodeTest` by name here (§9.10): it is the guard for the
    drill call, and the runner skips it.

**Phase 5 — drawer UI**
18. `LibraryDrawer.kt`: per-paper tri-state selection, `TriStateCheckbox`, two buttons, the
    two button-label strings (§7.1, §7.6).
19. `LibraryScreen.kt` `onStartStudy` (§7.2).
20. Extend `StudyActionStructureTest`, `StudyAndSelectionUiTest` (§9.6, §9.7).
21. Green: compiles locally and the pure-JVM structural tests pass; the androidTest additions
    execute on a device/emulator, not in this run. `NoDeadCodeTest` is in the §9.10 skip list,
    so invoke it by name before calling this phase green.

**Phase 6 — docs + full verification**
22. README updates (§10).
23. `sh /sdcard/repo/apk-build/build-apk.sh` and `sh /sdcard/repo/apk-build/run-jvm-tests.sh`.
24. Push; CI runs lint, the full suite and the Robolectric DB tests, and records the observed
    scoped-pull query count for the 501-category scope (§9.3).
25. Green: the local build and JVM suite passed in step 23; the five classes §9.10 shows the
    runner skips were invoked by name; CI is the authority for every Robolectric class.

---

## 12. Risk register

| Risk | Severity | Mitigation |
|---|---|---|
| Scoped SQL performs a full `card_state` scan instead of using `(paperId, dueAt)` | Medium | The driver stays `cs.paperId = :paperId` with the same indices; `q.categoryId IN (…)` is a post-filter on the joined row. Verify with `EXPLAIN QUERY PLAN` in the CI test, and keep the paper-wide path untouched. |
| `IN (…)` exceeds SQLite's variable limit | High | Chunk at 500 (`MAX_BIND_VARS`), the primitive already in the file (§5.4). Proof is a chunk-shape test in the pure-JVM `ChunkShapeTest`, not a DB test: Robolectric's SQLite allows 32766, and `ChunkedQueriesTest` is Robolectric so the local run skips it (N4, C2). |
| Chunked concatenation changes serving order | Medium | Chunks are consecutive slices of `orderedIds`; one chunk = the paper-wide order restricted to the scope. Document chunk-major, test set parity for nested papers and order parity for one-chunk scopes (§5.4, §9.3). |
| `Study.bestReview` diverges from the SQL ranking on an exact tie | Low | Implement it as `pools(...).reviews.firstOrNull()`; record that a cross-chunk tie may serve the other card. Paper-wide parity is unaffected (§5.4, L5). |
| Test-by-category semantics change (D1) surprises users | Low–Medium | Call it out in README and in the PR description; add a test that a selected parent now includes children. |
| Route breaks for ids containing commas | Low (pre-existing) | Documented in §8.7; optional hardening. |
| Cross-paper selection leak (existing bug) | Medium | Fixed by the per-paper map (§7.1.1) and by the `paperId` filter in `getOrderForCategories` (§7.1.2); covered by the source-shape test `theDrawerKeepsASelectionPerPaper` and the androidTest `tickingInTwoPapersKeepsTheirSelectionsApart` (§9.6, §9.7). |
| Adding `?categories=` changes the destination id | Low | A back stack restored across an app update may not resolve (§8.9). Accept, record in the PR, or pass the selection through a `SavedStateHandle`. |
| Lifting only `flattenCategories` leaves two copies elsewhere | Low | Deliberate (§4.1); the lifted KDoc names them so the next editor can decide. |
| Deeply nested categories overflow the recursive flatten | Low (pre-existing) | The scale test is wide, not deep; the recursive walk matches what composition already does. Do not build a 10k-deep fixture. |
| ViewModel key collision across scopes | Low | Include the joined ticked ids in `key` (§7.4). |
| Empty `categories=` deep link | Low | Parses to empty list → whole paper. |
| **The local runner silently omitted five test classes** | **High (misleading green)** | `run-jvm-tests.sh:25` used to grep the word `Robolectric` anywhere in the file, so comments hid `NoDeadCodeTest`, `MistakesTest`, `BackupStreamingReadTest`, `ScaledRowStorageTest` and `DownscaledImagesTraversalTest` (§9.10). **Fixed (C8):** the script now selects on `grep -q '^import org.robolectric'`, a comment can no longer defeat it, and the five run in a normal local pass — `classes=118 tests=932 failures=0`. A reintroduced word-match would show as a lower class count. |

---

## 13. Acceptance criteria

A reviewer can verify each of these by hand on a debug build:

1. Open the drawer, expand a paper with nested categories.
2. Tick a parent → every child and grandchild is ticked.
3. Untick one child → the parent shows indeterminate, and the child stays unticked; Study/Test
   buttons remain enabled and count the branch the learner ticked.
4. Tick that child again → the parent shows checked.
5. Tap **Study (N categories)** → the session serves only questions from that subtree;
   the header names the scope.
6. Finish/exit → the paper card's `Study (X due, Y new)` badge is **unchanged**.
7. Start Study on a different category → its cards appear; the first category's cards do not.
8. Bookmark a card from the scoped session, open Browse → the card is bookmarked (proves
   the session writes the same rows the paper-wide one would).
9. Kill the app mid-scoped-session and reopen the paper's Study → no crash, no lost
   schedule (a scoped session is not resumable by design; only tests are).
10. Tick the leaves of a branch one by one → the parent becomes checked and the branch's own
    questions are served; with every category ticked, counts and served cards match the paper
    card's badge.
11. Tick categories in paper A, collapse it, expand paper B → B's buttons count only B's
    ticks, and a test/study started from B contains no paper-A question (§7.1.1).

---

## 14. Out of scope (explicitly)

- **Per-category badges** on Library cards or in the drawer tree (D5). Possible follow-up:
  one `countsForCategories` call per *selected* set, not per node.
- **Category-scoped Stats.** The statistics screen stays paper/collection scoped.
- **Category-scoped mistakes.** `testRoute` carries categories for a drill, but the mistakes
  path ignores them: `TestViewModel.load` chooses `getMistakenQuestionOrder(paperId)` first
  (`TestViewModel.kt:204-210`), before it looks at `categoryIds`. Category-scoped mistakes
  would need that branch changed; the Study side gains only the main session and the tricky
  drill.
- **Persisting the last selection** between drawer openings (`remember`, not
  `rememberSaveable`).
- **Schema or migration changes.** None are needed: schedules are already per
  `(paperId, questionId)` and category membership is a join, not a stored column.
