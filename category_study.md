# Category-scoped Study — implementation plan

**Status:** proposed, not implemented.
**Scope:** let a learner start a Study session over **one or more categories** of a paper,
including **all descendants of any selected parent**, without touching Test mode's data or
any existing schedule.

---

## 1. Current behaviour (verified against the tree)

Study is scoped by paper id only. There is no category concept anywhere in the study
stack.

| Fact | Evidence |
|---|---|
| Study route carries no categories | `Routes.kt:28` — `studyRoute(paperId, leechesOnly)`; nav route `study/{paperId}?leechesOnly={leechesOnly}` (`McqNavHost.kt:152`) |
| Test route *does* carry categories | `Routes.kt:33` — `testRoute(paperId, categoryIds, …)` → `&categories=…` |
| Library Study buttons pass paper id only | `LibraryScreen.kt:411`, `:416` |
| Drawer category tree starts a **test** only | `LibraryDrawer.kt:326` — `onStartTest(paperId, categoryIds)` |
| Study store has no category parameter | `StudyStore.kt` — `grep -i category` returns nothing |
| Study DAO queries are paper-scoped | `Daos.kt:1335, :1351, :1369, :1388, :1420` — `WHERE cs.paperId = :paperId` |
| New-question DAO queries are paper-scoped | `Daos.kt:469, :518, :537` — `WHERE categories.paperId = :paperId` |
| Drawer checkboxes do **not** cascade | `LibraryDrawer.kt:249` `CategoryTree` toggles `node.id` only |

So today: *Test* can be started per category; *Study* cannot, and the category picker has
no parent/child cascade.

### 1.1 The one place a category already reaches questions

`getRefsByCategories` (`Daos.kt:399`) filters `questions.categoryId IN (:categoryIds)` —
**exact ids, no descendants**, because the checkbox set is not expanded. Any cascade work
therefore also needs a decision about Test (see D1).

---

## 2. Target behaviour

1. The drawer's per-paper category tree gains a **Study** action beside **Start test**.
2. Selection is **multi-select** (already possible) and **cascading**:
   - ticking a parent ticks its whole subtree;
   - unticking a parent unticks its subtree;
   - a partially-ticked parent renders **indeterminate**;
   - unticking one child of a parent makes the parent indeterminate;
   - ticking the last missing child makes the parent checked.
3. Starting Study with a selection serves **only cards whose question sits in the expanded
   set** — the selected categories plus every descendant.
4. Selecting **every** category of the paper behaves exactly like the paper's Study button.
5. Nothing selected → the Study button is not offered (the Test button already behaves this
   way, `LibraryDrawer.kt:219`).
6. The paper card's Study badge stays **paper-wide** and is unaffected by category sessions.
7. Study never writes attempts, so Test results, History and mastery are untouched.

---

## 3. Design decisions

| # | Decision | Recommendation | Why |
|---|---|---|---|
| **D1** | Does the cascade apply to **Test** as well? | **Yes.** One tree, one selection model. | A checkbox tree that cascades for Study but not Test is unexplainable. Both actions expand the same set. Document the change in README (Test-by-category now includes descendants). |
| **D2** | Descendant questions included? | **Yes**, always. | Matches the user request and the export/`CategoryFilter` precedent (`CategoryFilter.kt`: "descendants included"). |
| **D3** | Empty selection | Study button hidden; paper card Study remains whole-paper. | Mirrors `LibraryDrawer.kt:219`'s Test button. |
| **D4** | Leeches + categories | Categories and `leechesOnly` **intersect**. | The tricky drill on a subtree = "my problem cards in this subject". |
| **D5** | Library badge | Stays **paper-wide**. | Counts are computed once per paper (`LibraryViewModel.kt:64`); a per-selection badge would need a query per keystroke. Optional follow-up (see §11). |
| **D6** | Route encoding | Mirror `testRoute`'s `joinToString(",")` + `Uri.encode`. | Consistency; see the known comma limitation in §8.7. |
| **D7** | `IN (:ids)` > SQLite variable limit | **Chunk, in tree order.** | minSdk 26 ships SQLite with a 999-variable cap. `ChunkedQueries.kt` already exists for exactly this. Tree order keeps the slices stable (§5.4). |
| **D8** | Scope label in the Study header | 1 category → its title; N → "N categories". | The header already shows `state.paperTitle` (`StudyScreen.kt:445`). |

### 3.1 Selection model (why explicit ids, not expanded ids)

Store only the **explicitly ticked** ids in the drawer's state. The **effective** set is
`expand(nodes, explicit)` = every ticked id plus its whole subtree. This keeps a parent's
checkbox honest: unticking a child leaves the parent ticked in the explicit set, but the
effective set no longer covers the whole subtree, so the UI can show *indeterminate*
instead of silently re-ticking the child.

---

## 4. Domain layer

### 4.1 New file: `app/src/main/java/com/mcqapp/domain/CategorySelection.kt`

Pure, no Android types, no DB. This is where the cascade lives so it is unit-testable
without Robolectric (which matters: this host cannot run DB tests, see `AGENTS.md`).

**Reuse, don't reinvent.** Browse already solved "a node plus its descendants" over a
flattened tree:

- `BrowseScreen.kt:1029` — `flattenCategories(nodes, depth)` builds `(node, depth)` pre-order.
- `BrowseScreen.kt:1069` — `subtreeIdsOf(flat, rootId)` returns the subtree by scanning
  forward until the depth drops back to the root's, with a documented unknown-id rule
  (empty set, not "everything").

Both are `private` to `BrowseScreen`. Lift the flattening (and a `Set` variant of
`subtree`) into `CategorySelection`, then have `BrowseScreen` call it, so there is exactly
one pre-order definition in the app. That also means the cascade is exercised by Browse's
existing tests, not only by the new ones.

```kotlin
package com.mcqapp.domain

/** Checkbox state of one node in the category tree. */
enum class CategoryCheckState { CHECKED, UNCHECKED, INDETERMINATE }

/**
 * Cascading selection over a paper's category forest.
 *
 * The tree is the one Paper.categories already carries (CategoryNode, Models.kt:291),
 * so no ordering or parenting is re-derived here. [explicit] holds only the ids the
 * learner actually ticked; the effective set is [expand].
 */
object CategorySelection {

    /** Pre-order `(node, depth)` flattening. The single definition in the app. */
    fun flatten(nodes: List<CategoryNode>): List<Pair<CategoryNode, Int>>

    /** [rootId] and every descendant, in pre-order. Unknown id -> empty. */
    fun subtree(nodes: List<CategoryNode>, rootId: String): List<String>

    /** [explicit] plus every descendant of each ticked id. */
    fun expand(nodes: List<CategoryNode>, explicit: Set<String>): Set<String>

    /**
     * The ids a Study/Test session should carry: [explicit] expanded, in
     * tree order (DFS pre-order of Paper.categories). Chunked SQL reads
     * consecutive slices, so this is the order a scoped session serves in
     * (§5.4).
     */
    fun orderedIds(nodes: List<CategoryNode>, explicit: Set<String>): List<String>

    /**
     * Tick/untick [id]:
     *  - fully selected subtree  -> remove the whole subtree from [explicit]
     *  - anything else           -> add the whole subtree to [explicit]
     * Returns the new explicit set.
     */
    fun toggle(nodes: List<CategoryNode>, explicit: Set<String>, id: String): Set<String>

    /** One node's state. O(subtree); use [states] when rendering a whole tree. */
    fun state(nodes: List<CategoryNode>, explicit: Set<String>, id: String): CategoryCheckState

    /** Every node's state in one O(n) pass (§4.2). */
    fun states(
        nodes: List<CategoryNode>,
        explicit: Set<String>
    ): Map<String, CategoryCheckState>

    /** Title of [id] anywhere in the forest, or null. */
    fun titleOf(nodes: List<CategoryNode>, id: String): String?
}
```

Algorithm notes:

- `subtree` is implemented on the pre-order slice produced by `flatten` (the lifted
  `flattenCategories`): scan forward from the root until the depth falls back to the root's.
  `PaperStore.buildTree` (`PaperStore.kt:77`) already orders children, so pre-order is
  `(sortOrder, title)`.
- `state(id)`:
  - `UNCHECKED` when no id in `subtree(id)` ∪ `{id}` is in `expand(explicit)`;
  - `CHECKED` when every id in that closure is in `expand(explicit)`;
  - otherwise `INDETERMINATE`.
- `toggle` compares `state(id)` to `CHECKED`; `INDETERMINATE` means "make it fully on",
  which is what users expect from a tri-state box.
- `orderedIds` emits the `effOn` nodes from the same pre-order walk (§4.2), so it cannot
  disagree with `states()` or `expand()`. Tree order is *not* the flat
  `(sortOrder, title)` order the paper-wide SQL uses — `CategoryNode` does not even carry
  `sortOrder` — and §5.4 explains why that is fine and what it costs.

### 4.2 How `states()` stays linear

A naive `state(id)` per node is O(n²). `states(nodes, explicit)` does two passes over the
pre-order list:

1. **Forward pass.** For each node, `explicitly on = id in explicit`; after visiting
   children, `subtreeSize`, `onCount` (= own + children's `onCount`). This is a post-order
   fold and needs the flat list plus a parent stack — one pass, O(n).
2. **Classification.** `CHECKED` when `onCount == subtreeSize` … but that is only correct if
   `explicit` has no ancestor/descendant redundancy. It does: ticking a parent adds the whole
   subtree to `explicit`, then unticking a child removes the child's subtree, leaving the
   parent's id still explicit. So classify from the **effective** set:
   - `effOn(node) = id in explicit || parent is effOn`
   - `effOnCount(node)` = number of nodes in the subtree that are `effOn`
   - `CHECKED` if `effOnCount == subtreeSize`, `UNCHECKED` if `0`, else `INDETERMINATE`.
3. Reverse-order pass computes `effOnCount` per node in O(n), because
   `effOnCount(node) = (1 if effOn else 0) + sum(child.effOnCount)`.

`expand()` can then be built from the same walk (`effOn` nodes), so `orderedIds` and `states`
share one traversal and cannot disagree — the exact class of bug (a parent showing checked
while one child is off) that a two-implementation design invites.

Invariant worth a unit test: `states(n).values.count { it == CHECKED }` equals the number of
nodes whose whole subtree is effective; `orderedIds(...).toSet() == expand(...)`.

### 4.3 `SpacedRepetition.kt`

**No change to the scheduler.** `Study.pools` / `pickNext` / `onlyLeeches`
(`SpacedRepetition.kt:220-500`) already operate on whatever candidate list they are
given. The scoping is a query concern, not a memory-model concern.

One optional addition (used by §5.4's cross-chunk pick):

```kotlin
/**
 * The same comparator Study.pools applies to due reviews, exposed so a caller
 * that read one candidate per chunk can pick the global winner without
 * reimplementing "lowest retention, then oldest due".
 */
fun bestReview(
    scheduler: Scheduler,
    candidates: List<StudyCard>,
    now: Long
): StudyCard?
```

It can be implemented as `pools(scheduler, ids, states, now).reviews.firstOrNull()`, which
guarantees the cross-chunk winner is chosen by the *one* comparator the app already uses.

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

**Rule: copy the existing `…ForPaper` body verbatim and append one predicate.** Every one of
the five existing queries already joins `categories c` and already pins `c.paperId =
:paperId` (`Daos.kt:1333`, `:1356`, `:1371`, `:1391`, `:1413`), so the only edit is

```sql
AND q.categoryId IN (:categoryIds)
```

added to the `WHERE`, and the method renamed. That is deliberate: the paper-wide plan, the
index choice and the tie-break order stay identical, and a reviewer can diff the two
annotations by eye.

```kotlin
// countsForPaper (Daos.kt:1311-1335) + the predicate.
@Query(<countsForPaper verbatim> + " AND q.categoryId IN (:categoryIds)")
suspend fun countsForCategories(
    paperId: String,
    categoryIds: List<String>,
    now: Long
): StudyPopulation

// staleForPaper (Daos.kt:1348-1355) + the predicate.
@Query(<staleForPaper verbatim> + " AND q.categoryId IN (:categoryIds)")
suspend fun staleForCategories(
    paperId: String,
    categoryIds: List<String>
): List<QuestionIdHash>

// dueForPaper (Daos.kt:1360-1370) + the predicate; ORDER BY untouched:
//   "ORDER BY c.sortOrder, c.title, q.sortOrder, q.rowid"
suspend fun dueForCategories(
    paperId: String,
    categoryIds: List<String>,
    now: Long
): List<CardStateEntity>

// firstDueLearningForPaper (Daos.kt:1380-1392) + the predicate.
suspend fun firstDueLearningForCategories(
    paperId: String,
    categoryIds: List<String>,
    now: Long
): CardStateEntity?

// bestDueReviewForPaper (Daos.kt:1400-1421) + the predicate; the retention
// expression and `cs.dueAt ASC, c.sortOrder, c.title, q.sortOrder, q.rowid`
// tie-break are untouched.
suspend fun bestDueReviewForCategories(
    paperId: String,
    categoryIds: List<String>,
    now: Long,
    dayMs: Double
): CardStateEntity?
```

Do **not** rewrite these by hand — copy the annotation text, or the aliases (`q` vs `cs` vs
`c`) drift and Room's KSP compile check will not catch a silently different ordering.

### 5.3 `QuestionDao` additions

Same rule, but note these two use the bare alias `categories` (not `c`) and the implicit
join `INNER JOIN categories ON questions.categoryId = categories.id`:

```kotlin
// countForPaper (Daos.kt:465-471) + the predicate.
@Query(
    "SELECT COUNT(*) FROM questions " +
        "INNER JOIN categories ON questions.categoryId = categories.id " +
        "WHERE categories.paperId = :paperId " +
        "AND questions.categoryId IN (:categoryIds)"
)
suspend fun countForCategories(paperId: String, categoryIds: List<String>): Int

// firstNewQuestionForPaper (Daos.kt:505-520) + the predicate.
suspend fun firstNewQuestionForCategories(paperId: String, categoryIds: List<String>): String?

// newQuestionsForPaper (Daos.kt:522-540) + the predicate.
suspend fun newQuestionsForCategories(paperId: String, categoryIds: List<String>): List<String>
```

`countForCategories` **needs** the `paperId` parameter that the current `countForPaper` has;
passing ids alone would let a stale id from another paper inflate the fresh count. (That is
exactly the class of leak §7.1.1 fixes on the client side — belt and braces on the server
side too.)

Both new-question queries keep `ORDER BY categories.sortOrder, categories.title,
questions.sortOrder, questions.rowid`, and keep the
`LEFT JOIN card_state … AND card_state.paperId = categories.paperId` form. Keeping the join
matters: it is what makes the join *paper-scoped*, so a question edited in another paper can
never be treated as reset here.

### 5.4 Chunking (`app/src/main/java/com/mcqapp/data/local/ChunkedQueries.kt`)

`ChunkedQueries.kt` already exposes `getByIdsChunked`, `getForQuestionsChunked`,
`countUngradedForCategoriesChunked`. Add:

```kotlin
internal const val CATEGORY_CHUNK = 900     // < 999 SQLite variables, one slot for :now

/** Sums the four pool counts across chunks. */
internal suspend fun CardStateDao.countsForCategoriesChunked(
    paperId: String, categoryIds: List<String>, now: Long
): StudyPopulation

/** Union of stale rows across chunks. */
internal suspend fun CardStateDao.staleForCategoriesChunked(
    paperId: String, categoryIds: List<String>
): List<QuestionIdHash>

/** Concatenates consecutive slices of tree order (§5.4). */
internal suspend fun CardStateDao.dueForCategoriesChunked(
    paperId: String, categoryIds: List<String>, now: Long
): List<CardStateEntity>

/** First non-empty chunk wins: chunks are in tree order (§5.4). */
internal suspend fun CardStateDao.firstDueLearningForCategoriesChunked(
    paperId: String, categoryIds: List<String>, now: Long
): CardStateEntity?

/** One candidate per chunk, then Study.bestReview picks the global winner. */
internal suspend fun CardStateDao.bestDueReviewForCategoriesChunked(
    paperId: String, categoryIds: List<String>, now: Long, dayMs: Double,
    scheduler: Scheduler
): CardStateEntity?

internal suspend fun QuestionDao.firstNewQuestionForCategoriesChunked(...): String?
internal suspend fun QuestionDao.newQuestionsForCategoriesChunked(...): List<String>
```

**Ordering: what chunking does and does not preserve.**

`CategorySelection.orderedIds` walks the loaded tree depth-first (pre-order). That is *tree*
order. It is **not** the same as the SQL's `ORDER BY c.sortOrder, c.title, q.sortOrder,
q.rowid`, because `sortOrder` is per-parent (the tree does not even carry it —
`CategoryNode`, `Models.kt:291-300`, has no `sortOrder`). For a nested paper the flat SQL
order interleaves subtrees; pre-order does not. Example: root A (sortOrder 1) with child A1
(sortOrder 5) and root B (sortOrder 2) → SQL gives A, B, A1; pre-order gives A, A1, B.

That is acceptable, and the plan should say so explicitly rather than promise parity:

- Within one chunk, the SQL order is the real authored order (`c` is fixed per category, so
  it reduces to `q.sortOrder, q.rowid`).
- Chunks are consecutive slices of tree order, so concatenation is deterministic and stable.
- Therefore a scoped session serves a deterministic order that starts at the first selected
  branch and finishes its subtree before moving on — which is what a learner picking
  "Chapter 3" expects.
- **Flat papers (every category a root) — the overwhelmingly common case — are byte-identical
  to the paper-wide order.**

Consequences for the helpers:

- `dueForCategoriesChunked` / `newQuestionsForCategoriesChunked`: concatenate in chunk
  order. Deterministic; equal to paper-wide order for flat papers. Do not claim global
  parity in tests; compare row *sets* (and for flat papers, also order).
- `firstDueLearningForCategoriesChunked` / `firstNewQuestionForCategoriesChunked`: the first
  non-empty chunk's first row is exact *for this order*, which is the intended one.
- `bestDueReviewForCategoriesChunked`: order-independent. The global winner is necessarily
  the winner within its own chunk, so it is among the per-chunk winners; `Study.bestReview`
  applies the same comparator globally. Exact, no formula duplication.

### 5.5 `StudyStore` (`app/src/main/java/com/mcqapp/data/repository/StudyStore.kt`)

Add a scope parameter to the four read paths and to the reset path. Keep `paperId` first so
every existing call site reads the same.

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

suspend fun getStudyCounts(
    paperId: String,
    now: Long = System.currentTimeMillis(),
    categoryIds: List<String> = emptyList()
): StudyCounts
```

Internal branches (each private helper takes `categoryIds` and dispatches):

| Helper | Whole paper | Scoped |
|---|---|---|
| `resetEdited` | `staleForPaper` | `staleForCategoriesChunked` |
| `dueCards` | `dueForPaper` | `dueForCategoriesChunked` |
| `firstNewCard` | `firstNewQuestionForPaper` | `firstNewQuestionForCategoriesChunked` |
| `counts` | `countForPaper` + `countsForPaper` | `countForCategories` (summed) + `countsForCategoriesChunked` |
| pull learning | `firstDueLearningForPaper` | `firstDueLearningForCategoriesChunked` |
| pull review | `bestDueReviewForPaper` | `bestDueReviewForCategoriesChunked` |

`fresh` arithmetic in `counts` (`StudyStore.kt:296`) is unchanged — it is
`questions - (storedRows - newish)`, so summing `questions`, `storedRows` and `newish`
across chunks yields the same expression.

**Important:** `resetEdited` must be scoped too. It is currently paper-wide
(`StudyStore.kt:208`). Leaving it global means a single-category session rewrites the reset
rows of every edited question in the paper — correct but unbounded, and it would surprise a
caller studying a 1M-question paper's smallest category.

`recordStudyReview` (`StudyStore.kt:336`) is **unchanged**: schedules are keyed
`(paperId, questionId)` and a scoped session must not reshape storage.

### 5.6 `McqRepository` facade (`McqRepository.kt:653-687`)

Forward the parameter. Signatures keep `categoryIds` last with an empty default:

```kotlin
suspend fun getStudyQueue(paperId, now, leechesOnly, categoryIds = emptyList())
suspend fun getNextStudyCard(paperId, reviewsSinceNew, now, categoryIds = emptyList())
suspend fun getStudyEligibility(paperId, now, introduceNewCards, categoryIds = emptyList())
suspend fun getStudyCounts(paperId, now, categoryIds = emptyList())
```

No new repository method is required; `getPaper(paperId)` already returns
`Paper.categories`, which the Study screen uses for titles (§7.3).

---

## 6. Navigation

### 6.1 `Routes.kt`

```kotlin
fun studyRoute(
    paperId: String,
    categoryIds: List<String> = emptyList(),
    leechesOnly: Boolean = false
): String =
    "study/${encode(paperId)}" +
        "?categories=${encode(categoryIds.joinToString(","))}" +
        "&leechesOnly=$leechesOnly"
```

The empty default keeps `studyRoute("p1")` producing a valid route; `RoutesTest.kt:52`
asserts `"study/paper-1"` exactly, so either update that assertion or emit the query only
when non-empty:

```kotlin
fun studyRoute(paperId: String, categoryIds: List<String> = emptyList(), leechesOnly: Boolean = false): String {
    val query = buildList {
        if (categoryIds.isNotEmpty()) add("categories=${encode(categoryIds.joinToString(","))}")
        if (leechesOnly) add("leechesOnly=true")
    }
    return "study/${encode(paperId)}" + if (query.isEmpty()) "" else "?" + query.joinToString("&")
}
```

Prefer this second form: it preserves the existing route string byte-for-byte for the
whole-paper case, so `RoutesTest` only gains cases instead of changing.

### 6.2 `McqNavHost.kt:151`

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

---

## 7. UI

### 7.1 `LibraryDrawer.kt`

Current signature (`LibraryDrawer.kt:51-61`) gains one callback and the tree becomes
tri-state.

```kotlin
internal fun DrawerContent(
    …
    onStartTest: (paperId: String, categoryIds: List<String>) -> Unit,
    onStartStudy: (paperId: String, categoryIds: List<String>) -> Unit,
    onImport: () -> Unit
)
```

### 7.1.1 A pre-existing bug this work must fix

`checkedCategories` is a single drawer-wide `Set<String>` (`LibraryDrawer.kt:63`) that is
never cleared when a different paper is expanded:

```kotlin
val checkedCategories = remember { mutableStateOf(setOf<String>()) }
```

Tick two categories in paper A, collapse A, expand paper B, tick one: the button under B
reads *"Start test (3 categories)"* and passes **paper B's id plus paper A's category ids**.
`getRefsByCategories` (`Daos.kt:399`) filters on `questions.categoryId IN (…)` with **no
`paperId` predicate**, so a cross-paper test is genuinely possible.

The selection must therefore become **per paper**, not per drawer:

```kotlin
// paperId -> ids the learner explicitly ticked in that paper's tree
val explicitByPaper = remember { mutableStateOf<Map<String, Set<String>>>(emptyMap()) }

fun explicitFor(paperId: String): Set<String> = explicitByPaper.value[paperId].orEmpty()

fun toggle(paper: Paper, id: String) {
    val next = CategorySelection.toggle(paper.categories, explicitFor(paper.id), id)
    explicitByPaper.value = explicitByPaper.value + (paper.id to next)
}
```

`expandedPaperId` no longer needs to reset anything: each paper carries its own selection,
which is also nicer UX (collapsing a paper no longer loses the ticks). Do **not** clear the
map on collapse.

State changes from `Set<String>` to the per-paper model, with the effective set derived:

```kotlin
val explicit = explicitFor(paper.id)
val effective = CategorySelection.expand(paper.categories, explicit)
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

`states` is computed **once** per (paper, selection) by the caller, not per node:

```kotlin
val states = remember(paper.categories, explicit) {
    CategorySelection.states(paper.categories, explicit)
}
```

> A per-node `state(...)` call would make the drawer render O(n²): with the 1000-category
> target from `large_data.md`, 1000 subtree walks per recomposition is not acceptable.
> `states()` does one pre-order pass and one reverse pass (see §4.3).

Buttons, replacing `LibraryDrawer.kt:219-228`:

```kotlin
val selected = CategorySelection.orderedIds(paper.categories, explicit)
if (selected.isNotEmpty()) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { onStartStudy(paper.id, selected) }) {
            Icon(Icons.Default.PlayArrow, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.study_n_categories, selected.size))
        }
        OutlinedButton(onClick = { onStartTest(paper.id, selected) }) {
            Text(stringResource(R.string.test_n_categories, selected.size))
        }
    }
}
```

Note `selected` is `orderedIds`, not `explicit.toList()`. Today's code passes
`checkedCategories.value.toList()` (`:220`), whose `HashSet` order is arbitrary — harmless
for Test (the session re-sorts) but wrong for the chunked Study path, where chunk order *is*
the serving order (§5.4). `HashSet` order would make "which new card comes first" and the
drill's first row non-deterministic between launches.

Also replace the hard-coded `"Start test (${selected.size} categories)"` at `:222` with a
resource — the repo's `StringResourceUsageTest` forbids inline user-visible strings.

`onToggle` becomes:

```kotlin
onToggle = { id -> toggle(paper, id) }
```

### 7.1.2 Optional hardening of Test

Because §7.1.1 fixes the *caller*, the leak is gone. Adding `AND categories.paperId =
:paperId` to `getRefsByCategories` would make the DAO itself safe against a future caller
reintroducing the bug. Cheap, one parameter, and it composes with D1 (Test now receives
descendants). Recommended, in the same commit that changes Test semantics.


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
    private val categoryIds: List<String> = emptyList(),
    private val leechesOnly: Boolean = false
) : AndroidViewModel(application)
```

Changes:

1. `load()` (`:162`): pass `categoryIds` to `getStudyQueue`; log it. Resolve
   `paper.categories` into a scope label:
   ```kotlin
   val scopeLabel = when {
       categoryIds.isEmpty() -> ""
       categoryIds.size == 1 -> CategorySelection.titleOf(paper?.categories.orEmpty(), categoryIds.first()) ?: ""
       else -> application.getString(R.string.study_scope_many, categoryIds.size)
   }
   ```
   Store it on `StudyUiState` as `scopeLabel: String = ""`.
2. `nextCard()` (`:209`): `repository.getNextStudyCard(paperId, reviewsSinceNew, categoryIds = categoryIds)`.
3. `finishEmpty()` (`:261`): `getStudyEligibility(paperId, introduceNewCards = true, categoryIds = categoryIds)`;
   empty copy gains a scoped variant (see 7.4).
4. The drill branch (`:167-169`) passes `categoryIds` so the tricky drill is scoped too (D4).

### 7.4 `StudyScreen.kt`

Header (`:444-454`): under the paper title, render the scope when non-empty.

```kotlin
Column(Modifier.weight(1f)) {
    Text(state.paperTitle, … testTag("study-paper-title"))
    if (state.scopeLabel.isNotEmpty()) {
        Text(
            state.scopeLabel,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag("study-scope-label")
        )
    }
}
```

Empty state: add a scoped reason so "Nothing due right now." is not a lie about the whole
paper:

```kotlin
emptyReason = when {
    it.reviewed > 0 -> ""
    leechesOnly && categoryIds.isNotEmpty() -> ctx.getString(R.string.nothing_tricky_in_scope)
    leechesOnly -> ctx.getString(R.string.nothing_tricky)
    categoryIds.isNotEmpty() -> ctx.getString(R.string.nothing_due_in_scope)
    else -> ctx.getString(R.string.nothing_due)
}
```

(The current strings are inline literals at `StudyViewModel.kt:291-294`; extracting them is
in scope because the new variant must be translatable.)

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

---

## 8. Edge cases and invariants

### 8.1 Invariants to preserve

- **Badge/pull agreement.** `getStudyCounts` and `getNextStudyCard` must read the same
  predicate. Both get `categoryIds`; both use the same chunk helper.
- **No schedule writes for unseen questions.** `resetEdited` stays bounded by *edited*
  rows, now also by scope.
- **Schedules are keyed `(paperId, questionId)`** — a scoped session must not move or
  duplicate a card.
- **Study never writes attempts.** Unchanged: `recordStudyReview` only touches
  `card_state` + `review_log`.
- **A selection belongs to exactly one paper.** The drawer keeps
  `paperId -> explicit ids`; a Study/Test route must only ever receive ids that came from
  its own paper's tree (§7.1.1).
- **The route carries expanded ids only.** Re-expanding on the Study side would need the
  paper's tree *and* would double-apply if a caller already expanded; the route is the
  contract (mirrors `testRoute`).

### 8.2 Category deleted mid-session
The next query returns nothing for those ids → `pullCard` sees null → `finishEmpty`. No
special handling; the existing loop (`StudyViewModel.kt:219`) already tolerates a vanished
question.

### 8.3 Question moved out of scope mid-session
It simply stops appearing (the query filters by its *current* `categoryId`). No stale
schedule is written; its row stays for a later paper-wide session.

### 8.4 Selecting the whole tree
`orderedIds` yields every category, so counts and the *set* of served cards must equal the
paper-wide path. Ordering is equal only when every category is a root (§5.4). Pin both:
row-set equality always, row-for-row equality on a flat paper (§9.3).

### 8.5 Empty selection
The Study button is not rendered. A deep link with `categories=` (blank) parses to an empty
list → whole paper, which is the safe interpretation.

### 8.6 Leeches + scope
`getStudyQueue(…, leechesOnly = true, categoryIds)` filters the scoped due pools via
`Study.onlyLeeches` (`:500`), so it intersects correctly.

### 8.7 Known limitation: commas in category ids
`testRoute` today does `encode(categoryIds.joinToString(","))`. Navigation decodes the query
value, so an id containing a comma is indistinguishable from the separator. This is
pre-existing (`RoutesTest.kt:66` only covers ids *without* commas). Study inherits it by
mirroring the convention.

Optional hardening (recommended as a separate change so both routes move together):
encode each id individually and join with a separator that cannot appear in an id, e.g.
`US` (0x1F), then split on it after decoding. Or JSON-encode the list. Either way, change
`testRoute` and `studyRoute` in the same commit and add a `RoutesTest` case with `"c,1"`.

### 8.8 Chunk boundary at 900
An id list of exactly 900/901 must produce identical results. Covered by test (§9.3).

---

## 9. Tests

Naming follows the repo's convention: one behaviour per test, descriptive sentence names.

### 9.1 New pure-JVM test: `CategorySelectionTest.kt`

Runs on this host (no Robolectric).

| Test | Asserts |
|---|---|
| `tickingAParentTicksEveryDescendant` | `toggle` on root → `expand` contains the whole subtree |
| `untickingAParentClearsTheWholeSubtree` | toggle twice → empty |
| `untickingOneChildMakesTheParentIndeterminate` | `state(parent) == INDETERMINATE` |
| `tickingTheLastMissingChildMakesTheParentChecked` | transitions INDETERMINATE → CHECKED |
| `tickingAnIndeterminateParentChecksEverything` | INDETERMINATE + toggle → CHECKED |
| `expandIgnoresUnknownIds` | robust to a deleted category id |
| `orderedIdsFollowsTheTreeOrder` | pre-order matches `sortOrder`/`title` |
| `aForestWithSeveralRootsExpandsIndependently` | no cross-root leakage |
| `deepNestingIsFullyExpanded` | 5-level chain |
| `stateOfAnEmptyPaperIsUnchecked` | empty forest |
| `statesAgreesWithExpandForEveryNode` | `states()` classification matches `expand()` on a 1000-node forest (§4.2 invariant) |
| `statesIsLinearInTheNodeCount` | 10k-node forest completes without pathological cost (guards the O(n²) regression) |

### 9.2 New pure-JVM test: `StudyScopeLabelTest.kt` (optional)
`titleOf` resolution for 1 vs N categories, and the "N categories" formatting decision.

### 9.3 New Robolectric test: `StudyCategoryScopeTest.kt`

**CI-only** (x86_64) — record that in the class KDoc, matching `StudyPullQueryCountTest.kt:26`.

| Test | Asserts |
|---|---|
| `aScopedCountOnlyCountsTheSelectedCategory` | `getStudyCounts(p, categoryIds)` vs a seeded 2-category paper |
| `aScopedCountIncludesDescendants` | parent id alone counts child questions |
| `selectingEveryCategoryMatchesTheWholePaper` | scoped counts == paper-wide for `due/learning/leeches/fresh` |
| `aFlatPaperServesTheSameRowsInTheSameOrder` | row-for-row parity on a flat paper (§8.4) |
| `aNestedPaperServesTheSameRowsInTreeOrder` | set equality for nested, order == `orderedIds` order |
| `theScopedPullNeverServesOutsideTheScope` | 20 pulls, all ids in scope |
| `theScopedPullServesTheMostForgettableCardFirst` | matches `Study.pools` ordering |
| `theScopedNewCardIsTheFirstInTreeOrder` | `firstNewQuestionForCategories` follows `orderedIds` order |
| `aScopedResetOnlyTouchesEditedCardsInScope` | `card_state` rows outside scope unchanged |
| `nineHundredAndOneCategoriesChunkCorrectly` | count + pull parity at the 900 boundary |
| `aScopedLeechDrillIntersectsWithTheCategories` | leeches only, and only in scope |

Query-count regression (extend `StudyPullQueryCountTest.kt`): a scoped pull must not read
the answer key and must stay O(due rows), asserted with the existing Room query callback
(`StudyPullQueryCountTest.kt:98-121`).

### 9.4 Extend `StudyViewModelPullTest.kt` (Robolectric)

| Test | Asserts |
|---|---|
| `aCategorySessionStopsWhenItsScopeIsDry` | empty state reached while another category still has cards |
| `aCategorySessionLeavesOtherCategoriesSchedulesAlone` | other cards' `dueAt` unchanged |
| `theScopeLabelNamesASingleCategory` | `state.scopeLabel` |
| `theScopeLabelCountsSeveralCategories` | `state.scopeLabel == "2 categories"` |
| `resumingWithAScopeStillPullsOnlyThatScope` | new ViewModel instance, same ids |

### 9.5 Extend `RoutesTest.kt`

```kotlin
@Test fun aWholePaperStudyRouteIsUnchanged()      // "study/paper-1"
@Test fun studyCarriesCategories()                // categories=c1%2Cc2
@Test fun studyCarriesLeeches()                   // ?leechesOnly=true
@Test fun aScopedStudyRouteEncodesThePaperSegment()
```

### 9.6 Structural test: extend `StudyActionStructureTest.kt`

| Test | Asserts |
|---|---|
| `theDrawerOffersStudyBesideTest` | both callbacks invoked from the selected-categories block |
| `theCategoryTreeUsesATriStateCheckbox` | `TriStateCheckbox` source present |
| `theDrawerHasNoHardCodedActionLabels` | button labels come from `stringResource` |
| `theDrawerKeepsASelectionPerPaper` | ticking in paper A then paper B leaves paper B with its own count (§7.1.1) |

### 9.7 On-device: extend `app/src/androidTest/.../StudyAndSelectionUiTest.kt`

| Test | Asserts |
|---|---|
| `tickingAParentCategoryTicksItsChildren` | child checkbox is On |
| `studyFromTheDrawerServesOnlyTheSelectedCategory` | card stems belong to that subtree |

### 9.8 Tests that must keep passing untouched

`StudyQueueTest`, `DrillTest`, `StudySessionStateTest`, `StudyCountsInputTest`,
`StatsAggregatorTest`, `HistoryStatsParityTest`, `NoDeadCodeTest`. Their only risk is the
signature change; the empty default keeps them compiling.

---

## 10. Documentation

- `README.md` §Screens — drawer row: "per-paper category trees (reorderable),
  **test- and study-by-category**".
- `README.md` §Spaced repetition — a short subsection: "**Scoped study.** Pick categories
  in the drawer; a selected parent includes its subcategories. Badges stay paper-wide; the
  scope only decides what the session serves. Schedules are still per question, so studying
  a subtree never changes another category's dates."
- `README.md` §Edge cases — note that ending a scope does not reset anything, and that
  deleting a category mid-session ends the sitting.
- `README.md` §Known limitations (or the drawer paragraph) — record that category selection
  is now per paper (previously ticks leaked across papers, §7.1.1).
- `large_data.md` — add a line under "Fixed" once chunking lands (selection of >999
  categories).
- `AGENTS.md` — no change (build/test commands unchanged).

---

## 11. Implementation order

Each phase compiles and is independently reviewable.

**Phase 1 — pure domain (no behaviour change)**
1. Add `CategorySelection.kt`, lifting `flattenCategories`/`subtree` from `BrowseScreen`
   and pointing Browse at the lifted version (§4.1).
2. Add `CategorySelectionTest`.
3. Verify: `sh /sdcard/repo/apk-build/run-jvm-tests.sh` (runs locally).

**Phase 2 — Room scoped queries**
4. Add the 8 DAO methods (§5.2, §5.3).
5. Add chunk helpers to `ChunkedQueries.kt`.
6. Add `StudyCategoryScopeTest` (CI-only; author it, run on CI).
7. Verify locally: `sh /sdcard/repo/apk-build/build-apk.sh` (Room KSP validates SQL at
   compile time — a malformed query fails `kspDebugKotlin`).

**Phase 3 — repository + store**
8. Thread `categoryIds` through `StudyStore` and `McqRepository`.
9. Verify: compile; pure-JVM tests still green.

**Phase 4 — navigation + ViewModel**
10. `Routes.kt`, `McqNavHost.kt`.
11. `StudyViewModel`, `StudyViewModelFactory`, `StudyScreen`.
12. Extend `RoutesTest`, `StudyViewModelPullTest`.

**Phase 5 — drawer UI**
13. `LibraryDrawer.kt`: per-paper selection map (§7.1.1), tri-state tree, two buttons,
    resources.
14. `LibraryScreen.kt` `onStartStudy`.
15. Reuse the lifted flattening in `BrowseScreen` (§4.1) and, if accepted, add the
    `paperId` predicate to `getRefsByCategories` (§7.1.2).
16. Extend `StudyActionStructureTest`, `StudyAndSelectionUiTest`.

**Phase 6 — docs + full verification**
17. README updates.
18. `sh /sdcard/repo/apk-build/build-apk.sh`
19. `sh /sdcard/repo/apk-build/run-jvm-tests.sh`
20. Push; CI runs lint + full suite + Robolectric DB tests.

---

## 12. Risk register

| Risk | Severity | Mitigation |
|---|---|---|
| Scoped SQL performs a full `card_state` scan instead of using `(paperId, dueAt)` | Medium | The driver stays `cs.paperId = :paperId` with the same indices; `q.categoryId IN (…)` is a post-filter on the joined row. Verify with `EXPLAIN QUERY PLAN` in the CI test, and keep the paper-wide path untouched. |
| `IN (…)` exceeds SQLite's variable limit | High | Chunk at 900 (§5.4); boundary test at 900/901. |
| Chunked concatenation changes serving order | Medium | Chunk *consecutive slices* of `CategorySelection.orderedIds`; document tree order, test set parity plus flat-paper order parity (§5.4, §8.4). |
| `Study.bestReview` diverges from the SQL ranking | Medium | Implement it as `pools(...).reviews.firstOrNull()` so there is one comparator. |
| Test-by-category semantics change (D1) surprises users | Low–Medium | Call it out in README and in the PR description; add a test that a selected parent now includes children. |
| Route breaks for ids containing commas | Low (pre-existing) | Documented in §8.7; optional hardening. |
| Cross-paper selection leak (existing bug) | Medium | Fixed by the per-paper map (§7.1.1); regression test `theDrawerKeepsASelectionPerPaper`. |
| Lifting `flattenCategories` out of Browse regresses its filter | Low | Pure refactor, no behaviour change; Browse's own category-filter tests are the guard (§4.1). |
| ViewModel key collision across scopes | Low | Include the joined ids in `key` (§7.4). |
| Empty `categories=` deep link | Low | Parses to empty list → whole paper. |

---

## 13. Acceptance criteria

A reviewer can verify each of these by hand on a debug build:

1. Open the drawer, expand a paper with nested categories.
2. Tick a parent → every child and grandchild is ticked.
3. Untick one child → parent shows indeterminate, Study/Test buttons remain enabled.
4. Tick that child again → parent shows checked.
5. Tap **Study (N categories)** → the session serves only questions from that subtree;
   the header names the scope.
6. Finish/exit → the paper card's `Study (X due, Y new)` badge is **unchanged**.
7. Start Study on a different category → its cards appear; the first category's cards do not.
8. Bookmark a card from the scoped session, open Browse → the card is bookmarked (proves
   the session writes the same rows the paper-wide one would).
9. Kill the app mid-scoped-session and reopen the paper's Study → no crash, no lost
   schedule (a scoped session is not resumable by design; only tests are).
10. Select every category → counts and served cards match the paper card's badge.
11. Tick categories in paper A, collapse it, expand paper B → B's buttons count only B's
    ticks, and a test/study started from B contains no paper-A question (§7.1.1).

---

## 14. Out of scope (explicitly)

- **Per-category badges** on Library cards or in the drawer tree (D5). Possible follow-up:
  one `countsForCategories` call per *selected* set, not per node.
- **Category-scoped Stats.** The statistics screen stays paper/collection scoped.
- **Category-scoped drills / mistakes.** `testRoute` already supports categories for both;
  the Study side gains only the main session and the tricky drill.
- **Persisting the last selection** between drawer openings.
- **Schema or migration changes.** None are needed: schedules are already per
  `(paperId, questionId)` and category membership is a join, not a stored column.
