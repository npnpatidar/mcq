# Exam blueprint — implementation plan

Status: proposal, not scheduled. Supersedes **Appendix A.2** of `plan.md`
("Exam blueprint") and answers its seven open questions. No code has been
written for this feature; this document is the decision record and the
delivery plan.

Revision note: §3 decision #1 is **recursive**, not leaf-only. Paper
categories form a recursive tree (Science & Tech → Physics, Chemistry,
Biology, each with further children), so the blueprint mirrors that shape:
a blueprint section can itself have subsections, to any depth.

Baseline: `main` at `a706fcd` (merge of PR #55, haptics), database **v24**.
`plan.md` is unchanged at that commit. The two v24 facts this plan depends on:

- `attempts.negativeMarking` is now stored per attempt, so report marking no
  longer relies on the count-based reconstruction (`MIGRATION_23_24`).
- The report's margin uses `accuracyOnShown` (`correct / totalQuestions`), and
  `ExamStats` carries `latestShown` and `deltaMarginPoints`. A blueprint's
  section figures must use the same proportion, or the blueprint and the
  report will disagree about the same attempt.

## 1. The requirement

One paper ("GK") holds the whole general-studies bank in a **recursive
category tree**: Polity, History, Geography, Economics, Science & Tech,
Current affairs, and so on. Each may have its own subcategories — Science &
Tech → Physics, Chemistry, Biology — and those may themselves have children
(e.g. Physics → Mechanics, Optics). The same person is preparing for **three
exams at once**, and each exam weights the subjects differently at every
level: the parent share, and the split inside a parent.

They want to run a **mock that mirrors one exam's shape**, so that
"how did I do on UPSC" and "how did I do on SSC" are different, repeatable
measurements over the same bank — *including* the sub-section shape, e.g.
"Science is 30% of UPSC, and inside Science, Physics is two-thirds".

That means: **several named blueprints over one paper**, each blueprint being
a **tree of weighted sections** mirroring the paper's category tree:

| Blueprint | Polity | History | Geography | Economics | Science & Tech |
|---|---|---|---|---|---|
| UPSC | 25% | 20% | 15% | 10% | 30% → Physics 20% (of paper), Chem 6%, Bio 4% |
| SSC | 20% | 25% | 20% | 20% | 15% |
| State PSC | 30% | 30% | 20% | 10% | 10% |

A blueprint section may point at **any node** of the category tree, branch or
leaf, and may carry **children** that are strict descendants of that node.
A section with no blueprint children is *terminal*: it samples from the whole
subtree under its category. A section with children splits its quota among
them.

**Why it cannot be done today.** The three allocation policies that exist are:
`TestSelector.allocate()` (Balanced — proportional to *bank size*),
`equalAllocation()` (Coverage — equal per topic), and fixed pools (Tag,
Bookmarked, Slowest, Fixed panel — membership, unweighted). None lets the
learner say "History is 20% of this exam", let alone "Physics is two-thirds
of Science". Ticking categories and running Balanced samples History in
proportion to how many History questions happen to be in the bank, which is
not the exam's shape.

## 2. What already exists

This is why the feature is smaller than "a new test framework": most of the
plumbing is written.

| Piece | Where | What it gives us |
|---|---|---|
| Allocation pipeline | `TestSelector.balanced`, `.coverage`, `interleave`, `fillWith` | A blueprint is a fourth allocation policy feeding the **same** per-category `take(need)` → `interleave` → `fillWith` pipeline, applied recursively |
| Largest-remainder rounding + "at least one per topic" | `TestSelector.allocate` | The rounding rule to reuse at each level of the blueprint tree |
| Category tree expansion | `CategorySelection.orderedIds(nodes, tickedIds)` | Turns a branch section into its flat descendant id set, so the selector never walks the tree |
| Recorded composition | `Composition.of(vararg segments)` | Multi-segment fills are already stored, exported and replayed |
| Named, paper-scoped user object | `PanelEntity`, `PanelQuestionEntity`, `PanelDao`, panel picker in `TestSetupSheet` | The exact table/DAO/picker/backup template |
| Attempt identity for a named object | `attempts.variant` (`AttemptEntity`, `Attempt`, `McqBackupFormat` panels comment) | A blueprint id can ride on `variant`; **no attempts migration** |
| Resume determinism | `TestSnapshot.selectedQuestionIds` + `matches(... variant)` | Resume replays the frozen order; weights are never re-read |
| Route plumbing | `testRoute(type, count, exclusion, variant, categories, …)` | Blueprint id and length pass through unchanged |
| As-of-attempt report rows | `question_results.categoryTitle`, `TestReportBuilder.topicsOf` | Per-section reporting needs no new query if matching follows the category tree |
| Representative counters | `QuestionStatsDao.applyQuestionStats` reads `TestType.isRepresentative` | Marking `BLUEPRINT` representative makes `rep*` and the mock trend work automatically |
| Backup template | `McqBackupFormat.StudyFile.panels`, `writeStudyRows`, streaming sink | Blueprints are device state in `study.json`, like panels |

## 3. Decisions

These settle Appendix A.2's seven open questions and the five it did not ask
(8–12: weight storage, attempt identity, comparability, scope ownership, drift).

| # | Question | Decision | Why |
|---|---|---|---|
| 1 | Section shape | **Recursive tree**: one section = one category node (branch or leaf) + a relative weight + optional child sections that are strict descendants. Unbounded depth. | Paper categories are recursive (Science & Tech → Physics → Mechanics), so a flat leaf-only blueprint would force the user to re-list every leaf and could not express "Science 30%, Physics two-thirds of it". A tree mirrors both the bank and the exam shape, and a flat blueprint is the degenerate case (no children). |
| 2 | Family | **Representative** (`MOCK`), feeds `rep*` and the mock trend. | It is meant to be a mock. Practice would make the feature pointless. |
| 3 | Section pool | A terminal section draws **only from its own subtree** (all questions under its category, at any depth). An internal section's pool is the sum of its children's pools. A shortfall is redistributed to siblings at the same level proportionally to their weights; if no sibling has room, the deficit bubbles up to the parent's siblings; fill never leaves the blueprint's union. | Matches the fixed-panel rule ("a fixed set never fills from outside"). Borrowing subjects outside the exam would silently change the exam's shape. The per-level redistribution keeps parent totals honest when a child is thin. |
| 4 | Short section | The attempt **stays in the trend**, and the report states `asked 6 of 10` at the short node and its ancestors. | Demoting to practice would fragment the trend every time a bank section is thin. Honest labelling beats silent reclassification. |
| 5 | Timing | **Report only.** No per-section timers. | Per-section timing touches `TestSnapshot`, `remainingSeconds` and the session state machine — the largest hidden cost, and no value for one learner practising. |
| 6 | Weights | **Question-count proportions**, not scoring weights. | Scoring weights change `maxScore`, negative marking and every report denominator. Explicitly out. |
| 7 | Real example | The rows in §1, including one nested example (UPSC Science & Tech 30% → Physics 20%, Chem 6%, Bio 4%). Owner supplies the actual numbers. | Without them the editor and sampler are speculative. |
| 8 | Weight storage | **Relative integer parts per sibling group** (`Physics 2, Chemistry 1, Biology 1` = 50%/25%/25% *of Science*). Parts are relative within siblings, not global percentages. The editor displays both the local % and the implied global % (product down the path). | Mirrors the tree: a parent's share and the split inside it are independent knobs. No "must equal 100%" validation at any level; the editor normalises for display. |
| 9 | Blueprint identity on an attempt | `attempts.variant` = blueprint id; the human label also goes in the composition (`Composition.label`). | `variant` already persists a named object's id through import/export/backup, and the composition makes history readable after the blueprint is deleted. **No attempts column, no attempts migration.** |
| 10 | Comparability | Add `variant` to `ExamComparisonKey`. | Three blueprints can share a paper, a category union and a length. Without it they collapse into one trend. Subdividing a section without changing its union does **not** split the trend (see §8.2). |
| 11 | Scope ownership | A blueprint defines its **own** scope: when `BLUEPRINT` is selected the category tick-set is cleared and the scope is the union of all terminal subtrees. | Mirrors `PANEL_SCOPE_MESSAGE`. Removes the "blueprint inside a nested category selection" problem entirely. |
| 12 | Drift | Sections store `categoryId`s and follow the live tree: **deleting** a category removes its blueprint section **and its whole blueprint subtree** (database cascade); **moving** a category to another paper leaves the row and reports it as unavailable, so the plan drops that subtree. | A blueprint lives for months and should follow the bank. Panels freeze ids because a panel *is* a set; a blueprint is a rule. The cost is that a scope change splits its trend, which is honest. |

## 4. Domain model

### 4.1 New file `domain/Blueprint.kt`

```kotlin
/** One weighted section: a category node, its local weight, and its children. */
data class BlueprintSection(
    /** The category this section points at (branch or leaf). */
    val categoryId: String,
    /** Display title as it was when the plan was built; not part of identity. */
    val title: String = "",
    /** Relative weight within its sibling group; 1..999. */
    val parts: Int,
    /** Subsections. Empty makes this section terminal (samples its whole subtree). */
    val children: List<BlueprintSection> = emptyList(),
    /**
     * Expanded descendant category ids for sampling, filled when the plan is
     * built (empty on a stored blueprint). For a terminal section this is
     * `CategorySelection.orderedIds` of its category subtree.
     */
    val scopeCategoryIds: List<String> = emptyList()
) {
    val isTerminal: Boolean get() = children.isEmpty()

    /** Every terminal node in this subtree, depth-first. */
    fun terminals(): List<BlueprintSection> =
        if (isTerminal) listOf(this) else children.flatMap { it.terminals() }

    /** All category ids in this subtree (including internal nodes). */
    fun allCategoryIds(): List<String> =
        listOf(categoryId) + children.flatMap { it.allCategoryIds() }
}

/** A stored blueprint, as the picker and the editor see it. */
data class Blueprint(
    val id: Long,
    val paperId: String,
    val name: String,
    /** Top-level sections, ordered by the paper's category order. */
    val sections: List<BlueprintSection>,
    val createdAt: Long = 0
) {
    val terminals: List<BlueprintSection> get() = sections.flatMap { it.terminals() }
    val allCategoryIds: List<String> get() = sections.flatMap { it.allCategoryIds() }
}

/** The blueprint a run carries, resolved and frozen at Start. */
data class PlanBlueprint(
    /** The name shown on the report and the session ("UPSC"). */
    val label: String,
    /** Canonical tree (see §5.1 validation). */
    val sections: List<BlueprintSection>
) {
    val categoryIds: List<String>
        get() = sections.flatMap { it.terminals() }.flatMap { it.scopeCategoryIds }.distinct()

    val union: Set<String> get() = categoryIds.toSet()
}
```

`(label, sections)` is the frozen payload: the selector, the composition
and a resume never re-read the stored blueprint.

```kotlin
/** A section resolved against the live tree and the current question counts. */
data class BlueprintSectionView(
    val categoryId: String,
    val title: String,
    val parts: Int,
    /** Questions currently in this node's subtree (sum of children's pools). */
    val pool: Int,
    /** False when the category was moved to another paper; the whole subtree is dropped. */
    val available: Boolean,
    val children: List<BlueprintSectionView> = emptyList()
) { val isTerminal: Boolean get() = children.isEmpty() }

/** The picker's row: a blueprint without loading its sections. */
data class BlueprintSummary(
    val id: Long,
    val name: String,
    /** Count of all nodes (internal + terminal), not only terminals. */
    val sectionCount: Int
)
```

The setup-sheet preview reuses the view tree and adds a per-node quota;
define it as an extension of the view rather than a second tree type:

```kotlin
/** A view plus its resolved quota for the current length. */
data class BlueprintSectionPreview(
    val view: BlueprintSectionView,
    val requested: Int
)
```

Tree rules (enforced at save and at plan build):

- `parts >= 1` on every node; a node with the user deleting its parts is
  removed from the list (and its subtree with it).
- `categoryId`s are **unique across the whole blueprint** (internal + terminal).
- A child's `categoryId` is a **strict descendant** of its parent's
  `categoryId` in the paper's category tree (`CategorySelection` parent chain,
  or the nodes' `parentId` chain). A node that is not a descendant is refused
  (`SECTION_HIERARCHY_MESSAGE`).
- **Sibling subtrees are disjoint**: no two siblings have an ancestor/
  descendant relationship, and no top-level section is an ancestor of another
  top-level section (`SECTION_OVERLAP_MESSAGE`). This is what makes the
  terminal union well-defined.
- At least one top-level section exists; an internal node with `children`
  non-empty is valid, and a node with no children is valid (terminal).
- `name` is trimmed, non-empty, at most 60 characters; unique per paper.

### 4.2 Allocation: `domain/BlueprintQuotas.kt`

Pure, JVM-testable, no clock, no database. Allocation is **recursive**:
allocate at each sibling group by parts, then recurse into children.

```kotlin
object BlueprintQuotas {

    data class Allocation(
        /**
         * Per **terminal** category id: how many questions this run draws from
         * that terminal's subtree.
         */
        val perTerminal: Map<String, Int>,
        /** Sum of perTerminal: the largest run this blueprint can produce now. */
        val achievable: Int,
        val requested: Int
    ) {
        val complete: Boolean get() = achievable >= requested
    }

    fun allocate(
        sections: List<BlueprintSectionView>,
        count: Int,
        seed: Long = 0L
    ): Allocation
}
```

```kotlin
/**
 * Allocates [count] among one sibling group [views], caps by pool, and
 * recurses into any node that has children. Returns terminal quotas.
 * If a sibling cannot supply its share, the deficit is redistributed among
 * the remaining siblings with room (one extra per pass, ordered by remainder
 * then parts); a deficit no sibling can take bubbles up to the caller.
 */
private fun allocateLevel(
    views: List<BlueprintSectionView>,
    count: Int,
    seed: Long
): Map<String, Int>
```

Algorithm per sibling group, in order:

1. Keep views with `available && parts > 0 && pool > 0`. If none, the group
   gets 0. `totalParts = sum(parts)`. If `totalParts <= 0 || count <= 0`,
   return empty.
2. `exact_i = count * parts_i / totalParts`; `assigned_i = floor(exact_i)`,
   capped at `pool_i`.
3. When `count >= views.size`, seed every view with `pool_i > 0` at one
   question first (up to `count`): a listed subject must not disappear from a
   run that is long enough to include it.
   When `count < views.size`, include the top `count` views by
   `(parts desc, pool desc, seed tiebreak)` and give each exactly one — a short
   run follows the exam's emphasis instead of a random subset.
4. `left = count - sum(assigned)`. While `left > 0`, distribute one at a time
   to views with room, ordered by `(remainder of the exact share desc, parts
   desc, pool desc, categoryId asc)`; each view may receive at most one extra
   per pass, so the remainder spreads instead of piling onto the largest.
   Break when a full pass assigns nothing.
5. For each view that has children, recurse with `assigned_i` as its `count`
   (so the parent's deficit after step 4 is exactly what its children's
   combined pools show). For a terminal view, `assigned_i` is its quota.

Invariants, asserted in tests:

| # | Invariant |
|---|---|
| 1 | `sum(perTerminal) == min(count, sum(terminals' pools))` |
| 2 | Every terminal quota ≤ its pool; every internal quota = sum of its children's quotas |
| 3 | Each internal node's `requested` (the sum under it) never exceeds its subtree pool |
| 4 | Redistribution is per level: a thin child is exhausted first, its siblings take the surplus, and only a deficit no sibling can take bubbles up |
| 5 | Global share of a terminal = product of shares down its path (within floating-point rounding, the sum of terminal quotas under any internal node equals that node's allocation) |
| 6 | Deterministic for a given `(sections, count, seed)`; the seed only breaks exact ties |
| 7 | An unavailable node contributes 0 for its entire subtree and never blocks its siblings |

### 4.3 Composition v2

`Composition` records what the run actually did, as a **tree** mirroring the
blueprint, so the report survives blueprint edits and deletions.

```kotlin
@Serializable
data class CompositionSection(
    val categoryId: String,
    /** As-of-attempt title; the report joins rows on this (see §8.4). */
    val title: String,
    val parts: Int,
    /** Quota for this node (sum of children's quotas when internal). */
    val requested: Int,
    val children: List<CompositionSection> = emptyList()
)

@Serializable
data class Composition(
    val version: Int = VERSION,                 // 2
    /** "UPSC" for a blueprint attempt; null for every other type. */
    val label: String? = null,
    val sections: List<CompositionSection> = emptyList(),
    val segments: List<CompositionSegment> = emptyList()
) {
    companion object {
        const val VERSION: Int = 2
        /** Readers accept v1 (segments only) and v2. */
        fun fromJson(raw: String?): Composition?
    }
}
```

Compatibility:

- `Composition.fromJson` must accept `version in 1..VERSION`. A v1 row decodes
  with `label = null`, `sections = emptyList()`, `segments` as stored.
- An **older** app reading a v2 row hits its `version == 1` check and returns
  null ("no composition"), which every caller already tolerates. No crash, no
  migration of stored JSON.
- `Composition.fromJson` currently uses a default `Json{}`; the default is
  strict about unknown keys, so a v2 document with a **future unknown field**
  fails to decode and `fromJson` returns null. That is the correct fallback:
  the attempt loses its composition display, nothing else. Assert it in a test.

`fromJson`'s current guard is `it.version == VERSION && it.segments.isNotEmpty()`,
which must widen in two ways or v2 rows never parse:

1. `version` becomes `version in 1..VERSION` (an exact `VERSION` check would
   reject every existing v1 row the moment the constant is bumped).
2. The `segments.isNotEmpty()` requirement relaxes to "segments or sections
   non-empty", so a blueprint whose entire run is section quota (no
   `BLUEPRINT_FILL`) is still a composition.

New `FillSource` values: `BLUEPRINT` (a terminal's own quota) and
`BLUEPRINT_FILL` (rounding/shortfall fill inside the blueprint union).

`Composition.of(...)` stays as is (it is the no-blueprint path); a blueprint
builds the value directly from the tree.

## 5. Plan shape and routing

### 5.1 `TestPlan`

```kotlin
data class TestPlan(
    ...,
    /**
     * Non-null only for [TestType.BLUEPRINT]. Carries the label, the frozen
     * section tree and each terminal's expanded scope, so the selector, the
     * composition and a resume never re-read the stored blueprint.
     */
    val blueprint: PlanBlueprint? = null
)
```

Validation added to `TestPlan.validationError(available)`, in this order:

1. `blueprint` must be null for every type except `BLUEPRINT`
   (`SECTIONS_MESSAGE`).
2. `BLUEPRINT` requires a non-blank numeric `variant` (`VARIANT_REQUIRED_MESSAGE`,
   reusing the existing message) **and** a non-null `blueprint` with at least
   one top-level section (`SECTIONS_MESSAGE`).
3. Every `categoryId` in the tree must be **unique** across the whole blueprint
   (`SECTION_SHAPE_MESSAGE`).
4. Every child must be a **strict descendant** of its parent's category
   (`SECTION_HIERARCHY_MESSAGE`). `TestPlan` is pure and has no category
   nodes, so the parent-chain check lives in `TestSetup`/`TestViewModel`
   where the paper's tree is loaded; §5.4 builds the tree from the already
   validated stored blueprint, so after a repository load the check is the
   persisted invariant.
5. `parts >= 1` on every node (`SECTION_SHAPE_MESSAGE`).
6. Every terminal's `scopeCategoryIds` must be included in `categoryIds`, and
   `categoryIds` must equal the blueprint's union in canonical (category
   order) form (`SECTION_SCOPE_MESSAGE`).
7. `BLUEPRINT` with a ticked category scope that is not the section union is
   refused (`BLUEPRINT_SCOPE_MESSAGE`), mirroring `PANEL_SCOPE_MESSAGE`.

`BLUEPRINT` keeps the ordinary sampled-length rule (20..150 step 10); it is not
a frozen set.

`SelectionPreview` (used by the setup sheet) gains
`val sections: List<BlueprintSectionPreview> = emptyList()` — the view tree
plus per-node quotas. For a blueprint the preview's existing `pool` field
carries **`allocation.achievable`**, not the union size, so the sheet's
`available` (and therefore `resolve()`) clamps against what the sections can
actually supply. `TestSetupSheet.isCustom` gains `BLUEPRINT` so the pool comes
from the preview rather than the raw question pool.
`TestSelector.poolSize(BLUEPRINT)` stays the union size; it is a display
figure, while `achievable` is the startable one — the two are labelled
differently in the sheet.

### 5.2 `TestPlan.resolve`

A blueprint's `available` is the **achievable quota sum**, not the raw scope
size. Add a branch beside the fixed-panel one:

```kotlin
if (type == TestType.BLUEPRINT) {
    return Resolution.Ready(
        count = minOf(count, available),
        short = available < MIN_LENGTH,
        clamped = count > available,
        message = if (count > available) onlyNMessage(available) else null
    )
}
```

It must not round to a step of ten: if a section is one question short, the run
is 99, and the composition says so. A run below `MIN_LENGTH` is `SHORT` and
therefore never plotted in a mock trend (existing `familyOf`).

### 5.3 `TestType`

```kotlin
enum class TestType {
    ...,
    BLUEPRINT;

    // Similar to BLUEPRINT's additions below; the existing getters are not
    // overrides, they are plain `val`s on the enum.
    val isRepresentative: Boolean
        get() = this == RANDOM || this == BALANCED || this == BLUEPRINT

    val usesSignalSelector: Boolean
        get() = … || this == BLUEPRINT

    val supportsFreshSample: Boolean
        get() = … || this == BLUEPRINT
}
```

Consequences to state in the tests:

- `rep*` counters now include blueprint attempts (`applyQuestionStats` reads
  `isRepresentative`).
- `representativeNames()` includes `BLUEPRINT`, so Fresh-sample avoidance
  considers a recent blueprint run as recent practice of those questions.
- `practiceOf`/`practiceTrendOf`/`getPracticeHistory` return null/empty for it
  (they already guard on `isRepresentative`).
- `TestSetup.STARTABLE_TYPES` gains `BLUEPRINT`.
- `testTypeLabelRes`/`testTypeLabelText` gain a `BLUEPRINT` branch
  (`test_setup_type_blueprint`). Both use `else -> RANDOM`, so without the
  branch the code still compiles and a blueprint is silently labelled
  "Random" in the setup sheet and the session banner — the same class of bug
  the custom test types had. `StringResourceUsageTest` must stay green.

### 5.4 Entry points that construct a plan

Three sites, all must agree:

| Site | Change |
|---|---|
| `domain/TestSetup.kt` (`TestSetup`, `plan()`) | Add `blueprint: PlanBlueprint?` to the constructor data class and pass it through; `available` is supplied by the caller as the achievable sum; `comparability()` already returns `COMPARABLE` for a representative type |
| `ui/test/TestViewModel.kt` (`buildPlan`) | `variant` branch for `BLUEPRINT`: parse the id, load the blueprint's section tree once, expand each terminal with `CategorySelection.orderedIds`, build the `PlanBlueprint`, build the plan. A missing or empty blueprint is refused with a message, not started as Random |
| `ui/library/LibraryViewModel.kt` (`previewPlan`) | Build the preview plan with the same tree and the same expansions so the preview and Start cannot disagree |

Route: `testRoute(type = "BLUEPRINT", variant = "<id>", count, exclusion,
categories = sectionUnion)`. No new route parameter. `sectionUnion` is the
`PlanBlueprint.categoryIds` list (expanded terminal subtrees), so the session's
`scopeKey` is derived from the union, not from the tick-set.

The session-state key in `ui/test/TestSessionScreen.kt` is a fixed string
(`-type=…-count=…-exclusion=…-breadth=…-variant=…`); a blueprint flows through
it as `type=BLUEPRINT`, so no change is needed there. (`retainedKey` is a
free-standing key for `ViewModel` retention, not a format the session reads.)

Resume: `TestSnapshot.selectedQuestionIds` freezes the order and
`TestSnapshot.matches` already compares `variant` generically, so no snapshot
schema change is needed. The only new gate is the `when (type)` that maps the
route's `variant` in `TestViewModel.buildPlan`: add a `BLUEPRINT` branch that
requires a numeric id. Resume does **not** re-read blueprint weights — the
frozen order and the frozen composition are the truth.

## 6. Storage and migration (v24 → v25)

### 6.1 Entities

The section table stores a tree, so it needs a parent pointer to another
section row:

```kotlin
@Entity(
    tableName = "blueprints",
    foreignKeys = [ForeignKey(
        entity = PaperEntity::class,
        parentColumns = ["id"], childColumns = ["paperId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index(value = ["paperId", "name"], unique = true)]
)
data class BlueprintEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val paperId: String,
    val name: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "blueprint_sections",
    foreignKeys = [
        ForeignKey(
            entity = BlueprintEntity::class,
            parentColumns = ["id"], childColumns = ["blueprintId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"], childColumns = ["categoryId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = BlueprintSectionEntity::class,
            parentColumns = ["id"], childColumns = ["parentSectionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["blueprintId", "categoryId"], unique = true),
        Index("blueprintId"),
        Index("parentSectionId"),
        Index("categoryId")
    ]
)
data class BlueprintSectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val blueprintId: Long,
    val categoryId: String,
    /** Null for top-level sections. */
    val parentSectionId: Long? = null,
    /** Relative weight within its sibling group. */
    val parts: Int
)
```

Register both in `AppDatabase.entities`, bump `version = 25`, add
`MIGRATION_24_25` (two `CREATE TABLE` + the `CREATE INDEX` statements Room
would generate, the name and section indexes being `CREATE UNIQUE INDEX`), add
it to `MIGRATIONS`, and generate
`app/schemas/com.mcqapp.data.local.AppDatabase/25.json`.

The unique `(paperId, name)` index is deliberate where panels use a plain one:
three blueprints are chosen from a chip row, and two chips reading "UPSC" would
be indistinguishable. The repository catches `SQLiteConstraintException` and
returns the inline "a blueprint with this name exists" error; the editor never
has to guess.

Why `ON DELETE CASCADE` on `categoryId` (and transitively on
`parentSectionId`): a section over a deleted category is not a runnable rule,
so it leaves the blueprint with the category — and because its children point
at it, the whole blueprint subtree under that category leaves with it. The
`available = false` case that remains is a category **moved to another paper**:
the row is not deleted, the foreign key still holds, but the category is no
longer in this paper's tree. The repository's `blueprintSectionViews` reports
it as unavailable, the plan builder drops that subtree (and records the union
it actually used), and the editor offers to remove the stale row. State both
behaviours in the editor's help text.

Deleting a blueprint section in the editor deletes its whole subtree in the
same transaction (rows with `parentSectionId` pointing into it cascade).

### 6.2 DAO

```kotlin
@Dao
interface BlueprintDao {
    @Insert suspend fun insertBlueprint(row: BlueprintEntity): Long
    @Insert suspend fun insertSections(rows: List<BlueprintSectionEntity>): List<Long>
    @Query("DELETE FROM blueprint_sections WHERE id = :id")
    suspend fun deleteSection(id: Long)
    @Query("DELETE FROM blueprint_sections WHERE blueprintId = :id")
    suspend fun clearSections(id: Long)
    @Query("DELETE FROM blueprints WHERE id = :id") suspend fun delete(id: Long)
    @Query("SELECT * FROM blueprints WHERE paperId = :paperId ORDER BY createdAt, id")
    suspend fun forPaper(paperId: String): List<BlueprintEntity>
    @Query("SELECT * FROM blueprints ORDER BY createdAt, id")
    suspend fun all(): List<BlueprintEntity>
    @Query("SELECT * FROM blueprint_sections WHERE blueprintId = :id ORDER BY categoryId")
    suspend fun sections(id: Long): List<BlueprintSectionEntity>
}
```

`BlueprintDao` is a plain `@Dao` (not an `interface …Dao` mixin), registered
with `AppDatabase.abstract fun blueprintDao(): BlueprintDao`. The plain
`sections` query is used by the backup and by the repository's tree build;
ordering inside the tree is by the paper's category `sortOrder`, done in
Kotlin after loading the (small) section list and the paper's category nodes —
never by loading question rows.

Repository API on `McqRepository`, mirroring the panel methods:

```kotlin
suspend fun blueprintsForPaper(paperId: String): List<BlueprintSummary>
suspend fun blueprint(id: Long): Blueprint?
suspend fun createBlueprint(paperId: String, name: String, sections: List<BlueprintSection>): Long
suspend fun replaceBlueprint(id: Long, name: String, sections: List<BlueprintSection>)
suspend fun deleteBlueprint(id: Long)
suspend fun blueprintSectionViews(blueprint: Blueprint): List<BlueprintSectionView>
suspend fun buildPlanBlueprint(blueprint: Blueprint): PlanBlueprint?
```

`blueprintSectionViews` loads the paper's category nodes once
(`id, parentId, sortOrder, title`), counts questions per leaf category with
**one** grouped count query (reusing the existing category-count path), and
computes each node's subtree pool post-order in Kotlin. A paper's category
count is small (tens to a few hundred), so this is bounded; it never loads
question rows. Availability is `category.paperId == blueprint.paperId`.

`buildPlanBlueprint` reuses the same resolution and fills each terminal's
`scopeCategoryIds` with `CategorySelection.orderedIds(nodes, listOf(categoryId))`
(the node's subtree). A blueprint with no available terminal returns null and
Start is refused.

### 6.3 Migration test

`MigrationTest` (Robolectric, CI x86_64): hand-build the v24 schema, run
`MIGRATION_24_25`, open with Room (the identity hash is the assertion), then:

- a blueprint and its nested sections survive, in category order;
- deleting a mid-tree category cascades its section and its whole subtree;
- deleting the blueprint cascades all its sections;
- the chain 23 → 24 → 25 still restores a v23 file (one test, as the existing
  chain tests do).

## 7. Selection

Add to `TestSelector.select`:

```kotlin
TestType.BLUEPRINT -> blueprint(plan, signals)
```

```kotlin
private fun blueprint(plan: TestPlan, signals: List<Signals>): Selection {
    val blueprint = plan.blueprint ?: return Selection(emptyList())
    val inScope = signals.filter { it.categoryId in blueprint.union }

    // Pools per terminal: count signals whose leaf id is inside the terminal's
    // expanded subtree. Terminals are the blueprint tree's leaves-of-tree
    // (nodes with no blueprint children); their category subtree may be a
    // branch of the paper's category tree.
    val views = blueprint.sections.map { node -> viewOf(node, signals) }
    val allocation = BlueprintQuotas.allocate(views, plan.count, plan.seed)

    val perTerminal = blueprint.terminals.mapNotNull { terminal ->
        val need = allocation.perTerminal[terminal.categoryId] ?: 0
        if (need <= 0) return@mapNotNull null
        signals.filter { it.categoryId in terminal.scopeCategoryIds.toSet() }
            .shuffled(Random(plan.seed xor terminal.categoryId.hashCode().toLong()))
            .take(need)
    }
    val interleaved = interleave(perTerminal)          // existing helper
    val (ids, fill) = fillWith(
        selected = interleaved,
        target = plan.count,
        seed = plan.seed,
        pool = inScope,
        stages = listOf(FillSource.BLUEPRINT_FILL to inScope)
    )
    return Selection(
        ids = ids,
        composition = Composition(
            label = blueprint.label,
            sections = blueprintTreeOf(blueprint, allocation),
            segments = Composition.of(
                FillSource.BLUEPRINT to interleaved.size,
                *fill.toTypedArray()
            ).segments
        )
    )
}
```

Notes:

- The per-terminal shuffle seed is the existing convention
  (`plan.seed xor categoryId.hashCode()`), so a resumed plan and a fresh plan of
  the same seed agree at every level of the tree.
- `interleave` keeps sections from blocking each other in the presented order,
  exactly as Balanced/Coverage already do.
- `fillWith` stays inside `inScope` (the blueprint's union), so a blueprint never
  asks a question outside its own sections.
- Nothing is re-read from the database: the label, titles, parts and expanded
  scopes come from the plan, which was frozen at Start. That is what makes the
  composition and a resume agree even if the stored blueprint is edited or
  deleted between runs.
- Empty pools: a terminal with `pool = 0` gets 0 and the recursive allocation
  redistributes at each level. `views` is never empty because validation
  refuses a blueprint with no sections.
- A missing or empty `pool` never makes the selector throw: `blueprint()` returns
  an empty `Selection` and Start is refused upstream by `resolve()`. Never fall
  back to Random or to the whole paper.

`TestSelector.poolSize(TestType.BLUEPRINT)` → the union size.
`TestSelector.preview` returns the allocation as the composition, so the setup
sheet shows the quotas **before** Start.

`signals` is read as membership in a terminal's expanded id set
(`categoryId in scopeCategoryIds`), not equality — this is the tree expansion
the leaf-only design avoided, and it is why `scopeCategoryIds` is frozen into
the plan. Both reads are bounded by the scope.

## 8. Counting, families and comparability

### 8.1 Comparability key

```kotlin
data class ExamComparisonKey(
    val paperId: String,
    val testType: String?,
    /** The named object's id for BLUEPRINT; null for every other type. */
    val variant: String?,
    val scopeKey: String,
    val requestedCount: Int,
    val samplingVersion: Int,
    val exclusionMode: String
)
```

No historical re-grouping: `TestPlan.validationError` only allows `variant` on
TAG, AUTHORED_DIFFICULTY, COVERAGE and FIXED_PANEL, which are all practice types,
so **every existing representative attempt has `variant = null`** and its key is
unchanged. Add a test asserting exactly that (a v24 Random attempt keeps its
group).

### 8.2 Two blueprints, two trends

With three blueprints over the same categories and the same length,
`ExamComparisonKey.of` must differ by `variant`. Test both directions:

- same blueprint, two runs → same key, one trend;
- UPSC vs SSC with an identical category union → different keys, separate trends;
- a blueprint renamed → same key (the id did not change), one trend;
- a blueprint's sections edited → **same key**, and the report shows the new
  composition. This is deliberate: renaming or reweighting a subject does not
  create a new measurement series, but the composition on each attempt records
  what that attempt actually asked. If the union of terminal subtrees changes
  enough to change `scopeKey`, the trend splits — state this.
- **Subdividing a section without changing its union** (e.g. adding
  Physics/Chem/Bio under Science, covering the same subtree Science already
  covered) keeps the same `scopeKey` and therefore the same trend. The whole
  blueprint score stays comparable; only the section breakdown changes. This
  is deliberate: the sampling pool did not change. If the user wants a fresh
  series after re-subdividing, they duplicate the blueprint (new id).

### 8.3 `ExamStats` profiles

Today `TestProgressData.exams` is `Map<ExamGroup, ExamStats>` (one `ExamStats`
per group, each built from the group's latest key) and `ExamSection` selects a
group and reads its single `ExamStats`. Both Strict mocks and Fresh mocks are
`ReportFamily.MOCK`/`FRESH_SAMPLE`, so the three blueprints all land in one
group; the map keyed by `ExamGroup` cannot hold them side by side without
overwriting. The tab must therefore carry profiles per group.

- `ExamStats` gains `label: String?`, read as
  `Composition.fromJson(attempt.compositionJson)?.label` (pure, no query,
  survives blueprint deletion) and fall back to the type label.
- `TestProgressData.exams: Map<ExamGroup, List<ExamStats>>`, latest first,
  capped at 8 per group, each built from its own comparison key.
- `McqRepository.buildExamStats` walks the distinct keys in the group and calls
  `ExamCategoryTotals`/`ExamDwellTotals` per key (the lifetime counters are per
  paper and computed once). Bound: ≤ 8 keys × 3 groups, each an aggregate over
  that key's attempt ids — the same chunked queries that exist now.
- `ExamSection` keeps its group chips and gains a profile chip row; selecting a
  profile swaps the displayed `ExamStats` with no re-query.

`TestProgressData.exams` is built in `McqRepository.buildExamStats` — one
`ExamStats` per `ExamGroup` — and read by `ui/stats/sections/ExamSection.kt`,
which selects a group and reads its single `ExamStats`. It is **not** a streamed
accumulator, so there is no `StatsAggregator`/`StatsStore` parity case to write
(the plan's "two builds, one accumulator" rule does not apply here).

### 8.4 Report

`TestReport` carries `val blueprint: BlueprintReport? = null`, null for every
non-blueprint attempt (and therefore for every existing report and every
existing test).

```kotlin
data class BlueprintSectionResult(
    val title: String,
    val requested: Int,
    val asked: Int,
    val correct: Int,
    val smallSample: Boolean          // asked < TestReportBuilder.SMALL_TOPIC
) {
    /** The quota was fully supplied by the bank. */
    val complete: Boolean get() = asked >= requested
    val accuracy: Double get() = if (asked > 0) correct.toDouble() / asked else 0.0
    /** Subsections as asked. */
    val children: List<BlueprintSectionResult> = emptyList()
}

data class BlueprintReport(
    val label: String,
    val sections: List<BlueprintSectionResult>,
    /** Every node got its full quota. */
    val complete: Boolean,
    /** Rows in the attempt whose title matched no section. */
    val unresolved: Int = 0
)
```

Built in `TestReportBuilder.build` from the composition's section **tree**
joined to the `ReportRow`s.

`question_results` stores no `categoryId`, only `categoryTitle` (`QuestionResultEntity`),
and `ReportRow` exposes nothing else, so a deleted or moved question still lands in its
section by title. Matching by title is what keeps the section table reconciling with
`score.shown` after the bank is reorganised. With a tree:

- Each row's leaf `categoryTitle` matches a **terminal** section by title. The
  row's counts then roll up the path: every ancestor's `asked`/`correct`/
  `requested` is the sum of its descendants.
- When the question still resolves (the `ExamCategoryTotals` join to
  `questions`), prefer matching by **current leaf id** to disambiguate two
  leaf nodes that share a title under different parents; fall back to title
  for deleted/moved questions. The report's `unresolved` count is the rows
  that match neither, using the same "N results no longer resolvable"
  convention as `unresolvedResults`.
- `asked` is the count of rows with that title; `correct` counts correct rows. Do not
  reuse `topicsOf`: it groups ungraded and skipped rows differently and would disagree
  with the composition's request.
- `smallSample = asked < TestReportBuilder.SMALL_TOPIC` (5), and `accuracy = correct / asked`.
  The margin on a node comes from `TestStatistics.marginPoints(correct / asked, asked,
  null)`; the composition is a quota plan, not a random sample, so no finite-population
  correction applies.

The existing `topics` table stays: it is the as-of-attempt category detail. The
blueprint card sits above it with `requested / asked` and the completion note.

`TestReport.marginPoints`/`accuracyOnShown` (v24) are unchanged: a blueprint is
still a sample of the paper, so the whole-attempt margin keeps the
finite-population correction against the scope. The per-section margins in
§9.4 deliberately drop it, because a section is a quota, not a random sample.

## 9. UI

### 9.1 `ui/library/BlueprintEditorSheet.kt` (new)

A modal sheet, opened from the Test setup sheet:

- **Name** field (60 chars, unique per paper, inline duplicate error).
- **Tree list**: the paper's full category tree, indented by depth, each row
  with a numeric stepper/field in `parts` (0 = not in the blueprint). Every
  node is selectable (branch or leaf). Selecting a branch includes its whole
  subtree by default; selecting a child implies its parent is included.
- **Local vs global share**: each row shows `parts → local %` (within its
  sibling group) and → global % of the blueprint (product down the path).
  The quota line reads `Science & Tech · 30 parts · 30% · 30 questions` at the
  parent, and `Physics · 2 of 5 · 40% of Science · 20% of paper · 20 questions`
  at the child.
- **Hierarchy validation inline**: a child cannot be added outside its parent,
  siblings cannot overlap, and a node cannot be its own descendant. The row
  says why a selection is refused.
- **Quota preview**: for the currently selected length, each node's resolved
  quota and any shortfall (`Economics 12 of 15`).
- **Total** line: top-level `sum(parts)` and the implied top-level percentages.
- **Save / Delete** (delete confirms, like panels). Deleting a blueprint
  section deletes its whole subtree.

The editor is deliberately separate from `TestSetupSheet` (already 900 lines).
If the paper has no categories, the editor shows the "this paper has no
questions" empty state instead of an empty tree.

### 9.2 `ui/library/TestSetupSheet.kt`

- New parameters: `loadBlueprints`, `loadBlueprintSections`, `onCreateBlueprint`,
  `onUpdateBlueprint`, `onDeleteBlueprint`.
- A `BLUEPRINT` chip beside Random/Balanced. Selecting it:
  - clears the category tick-set (the blueprint owns the scope),
  - loads the paper's blueprints into a chip row (like panels), plus "New…",
  - loads the chosen blueprint's tree and shows the **resolved section table**
    (indented, with per-node quotas) and the achievable count before Start,
  - shows `Blueprint · UPSC · 100 questions` in the start summary.
- A subtree whose category was moved to another paper is shown struck through
  with "section ignored"; the run continues with the rest and the preview states
  the reduced achievable count. A deleted category removes its subtree from the
  blueprint (§6.1), so it never appears here.
- If the achievable count is below 20, Start is refused with the short-scope
  message, exactly like a small paper today.
- The Fresh-sample switch is available for a blueprint, with a warning that
  excluding recent questions can leave a section short.

### 9.3 Session header

`TestSessionScreen` banner: a blueprint reads
`Mock · Blueprint · UPSC · 100 questions` (the banner logic already names any
bounded type except a plain Random run).

### 9.4 Report screen

`ResultsScreen`: a `Blueprint` card between the header and the topics card:

```
Blueprint · UPSC                          not complete: Economics 12 of 15
Polity           25 asked / 25  80%   ±16
History          20 / 20       70%   ±20
Geography        15 / 15       60%   ±24
Economics        12 / 15       50%   ±28   short by 3
Science & Tech   28 / 30       64%   ±18
  Physics        20 / 20       70%   ±20
  Chemistry       6 / 6        50%   ±39
  Biology         2 / 4        50%   ±69   short by 2
```

A node with `asked < 5` gets the existing "small sample" note, not a
mastery claim (note how deep nodes quickly fall under 5 even on a 100-question
mock — that is the measurement-honesty point in §15). The `±` is
`TestStatistics.marginPoints` at 95% — it is a sampling statement about what
could be inferred, not a guarantee about the next run, and the card carries
that wording.

### 9.5 Tests/Exam tab

`ui/stats/sections/ExamSection.kt`:

- group chips unchanged (Strict / Fresh / Practice);
- a second chip row of **profiles** when the selected group has more than one:
  `Random`, `Balanced`, `UPSC`, `SSC`, `State PSC`. Selecting one swaps the
  `ExamStats`; no re-query.
- the KPI header shows the profile label and its length, so two 100-question
  blueprints are not mistaken for each other;
- the per-section accuracy bars come from the existing category rows, rolled
  up the blueprint tree (a parent bar is the sum of its children's rows), so a
  blueprint profile automatically shows its sections.

### 9.6 Strings

New `values/strings.xml` entries for: type label, editor titles/labels/errors,
setup summary, session banner, report card and the completion line. Keep
`StringResourceUsageTest` green (every added string is rendered from code).

No new test dependency is added for localization or for anything else in this
feature; only `values/strings.xml` plus the compose code that renders it.

## 10. Backup and import/export

**Blueprints are device state, not importable content.** They follow panels
exactly: written to `study.json`, never to `content.json`, never to the Anki
`mcqapp` field.

- `McqBackupFormat.StudyFile` gains
  `val blueprints: List<BlueprintRow> = emptyList()`, with

  ```kotlin
  @Serializable
  data class BlueprintRow(
      val id: Long,
      val paperId: String,
      val name: String,
      val createdAt: Long,
      val sections: List<BlueprintSectionRow> = emptyList()
  )
  @Serializable
  data class BlueprintSectionRow(
      val categoryId: String,
      val parts: Int,
      val children: List<BlueprintSectionRow> = emptyList()
  )
  ```

- `writeStudyRows(...)` gains a `blueprints` parameter and writes
  `,"blueprints":[…]` after `panels` (the function hand-writes the JSON, so
  the comma placement is the change).
- The streaming reader gains `"blueprints" -> stream.readArray { sink.blueprint(...) }`;
  `StreamingSink` gains `suspend fun blueprint(row: BlueprintRow) {}`.
- Restore inserts blueprints after the content (categories must exist). Ordering
  against attempts does not matter: attempts come from `content.json` and only
  carry the id on `variant`, so a blueprint can be written after them. Ids are
  preserved either way. Section ids are reallocated on restore and the tree is
  re-linked by `categoryId` (the backup stores the tree shape, not section ids).
- **No `FORMAT.VERSION` bump**: `StudyFile` is a lenient default-class reader
  (`ignoreUnknownKeys = true`); a missing `blueprints` field decodes to an
  empty list, so an older `study.json` restores cleanly.
- Import of a foreign `.mcq`/`.apkg` carries attempts with `variant` but no
  blueprint definition. The Tests tab then shows the composition label (stored
  in the attempt), so history still reads correctly; only the picker is empty.
  This is the same behaviour as an imported panel id.

## 11. Tests

### 11.1 Pure JVM (local)

`BlueprintQuotasTest`:

- flat allocation: proportional quotas, largest remainder, min-one-per-section;
- `count < sections.size` → the top `count` sections by `parts` get one each,
  the seed only breaks ties;
- a section pool smaller than its quota is exhausted and the surplus moves to
  the others in `parts` order, not `pool` order;
- **nested allocation**: Science 30% split Physics 2 / Chem 1 / Bio 1 produces
  the right terminal quotas; a thin Physics redistributes to Chem/Bio first and
  only bubbles up to Polity/History if no sibling can take it;
- a parent's quota equals the sum of its children's quotas; global share is the
  product down the path;
- `sum(perTerminal)` equals `min(count, sum(terminals' pools))` on random cases
  (property test over a small matrix, including a 3-level tree);
- an unavailable node contributes 0 for its whole subtree and never blocks its
  siblings;
- determinism: same `(sections, count, seed)` → same allocation; different seed
  only changes ties.

`BlueprintSelectorTest` (extends `TestSelectorTest`):

- each terminal receives exactly its quota when the pool allows;
- the union is respected: no selected id outside the blueprint's expanded
  subtrees (a terminal at a branch samples its whole subtree);
- fill is recorded as `BLUEPRINT_FILL`, never another section's quota;
- the composition carries the blueprint label and the **tree** of requested
  quotas;
- composition v2 round-trips; a v1 JSON still parses with empty sections.

`TestPlanTest` additions:

- `blueprint` refused for every non-blueprint type;
- blueprint without a variant (or with a non-numeric variant) refused, and a
  blueprint with no sections refused;
- duplicate `categoryId` anywhere in the tree refused;
- `parts < 1` on any node refused;
- `resolve()` clamps to the achievable count without rounding to 10, and marks
  `short` below 20;
- a blueprint with a ticked scope that is not the section union is refused;
- a **non-blueprint** plan's `validationError(available)` is unchanged for every
  `available` in 0..200 compared with the current implementation (the regression
  guard for the shared class).

`BlueprintTreeTest` (new, repository helpers in pure form):

- a child that is not a strict descendant of its parent is refused;
- overlapping siblings (ancestor/descendant across siblings) are refused;
- a child implies its parent (the tree build adds missing ancestors);
- the union of a tree equals the union of its terminals' expanded subtrees;
- subdividing a section without changing its union leaves the union unchanged.

`ExamStatsTest` additions:

- `ExamComparisonKey` separates two blueprints with the same union;
- subdividing a blueprint without changing its union keeps the same
  `scopeKey`, so the trend does not split (documented behaviour, §8.2);
- a v24 Random attempt's key is unchanged (`variant = null`);
- the profile label is read from the composition and survives a missing
  blueprint;
- `TestProgressData.exams` returns profiles latest-first and caps at 8.

`TestReportBuilderTest` additions:

- the blueprint card maps rows to terminals by as-of-attempt title and rolls
  them up the tree; a question moved or deleted still lands in its section,
  and an unmatched title is counted as unresolved;
- a short node is `requested=15 asked=12`, `complete=false`, and the section
  rows reconcile with `score.shown`;
- a node with 4 asked is `smallSample = true`;
- a non-blueprint report has `blueprint = null`.

`BackupRulesTest` / `BackupRestoreRoundTripTest` additions:

- a nested blueprint survives a backup/restore (tree shape, parts, and ids);
- a v1 `study.json` without `blueprints` restores (empty);
- a newer `study.json` with an extra unknown field still restores.

### 11.2 Robolectric/DB (CI x86_64)

`BlueprintDbTest`:

- create/replace/delete; sections cascade with the blueprint, with a category
  delete (whole subtree), and with a section delete (subtree);
- a moved category leaves the section and is reported unavailable (its subtree
  too);
- `blueprintSectionViews` pool counts match a seeded recursive bank (subtree
  counts, not leaf-only);
- saving a blueprint attempt writes `variant`, a `compositionJson` with
  `label` and the section tree, and updates `rep*` (representative);
- `ExamComparisonKey` grouping in SQL/aggregate terms: two blueprints produce
  two groups, and the category/dwell aggregates are requested per key;
- **the existing-path guard**: one test that seeds only a Random attempt
  (no blueprint) and asserts `getExamCategoryTotals`, `getExamDwellTotals`,
  `getExamCounterTotals` and `ExamStatsBuilder.build` return exactly what they
  returned before this feature (the blueprint's `rep*` rows are the only
  change, and they are assertable separately).

`MigrationTest` additions: §6.3.

### 11.3 Instrumented (CI emulator)

`BlueprintPathsUiTest`:

- create a nested blueprint (Science → Physics, Chemistry) in the editor, start
  it, and assert the session banner and the report's section tree;
- delete the blueprint and assert the Tests tab still shows the attempt's label
  from the composition;
- assert a blueprint with a section larger than its pool shows the shortfall in
  the setup preview before Start.

### 11.4 Guard tests that must not regress

- `StringResourceUsageTest`.
- `TestTypeLabels` exhaustiveness: Kotlin `when` over an enum is exhaustive,
  but both label helpers use `else -> RANDOM`, so a missing `BLUEPRINT` branch
  compiles and silently mislabels the type. `test_setup_type_blueprint` is
  asserted in a unit test, not only by compilation.
- `TestPlan` / `Composition`: §11.1's `available` 0..200 guard and the v1
  composition round-trip.
- `McqRepository.getReportEvidence` / `getPracticeHistory`: they guard on
  `isRepresentative`; a blueprint must return `emptyList()`/null from the
  practice path exactly like Random and Balanced.

## 12. Delivery plan

Four PRs on `feature/*`, each with its own tests and green CI. Each PR updates
`plan.md` and this file.

### PR 1 — Domain and selection (no storage, no UI)

- `domain/Blueprint.kt` (`BlueprintSection` tree, `Blueprint`,
  `PlanBlueprint`, `BlueprintSectionView` tree, `BlueprintSummary`,
  `BlueprintSectionPreview`), `domain/BlueprintQuotas.kt` (recursive)
- `Composition` v2 (`label`, `sections` tree; read v1..v2)
- `FillSource.BLUEPRINT`, `.BLUEPRINT_FILL`
- `TestPlan.blueprint` + validation + `resolve` branch
- `TestType.BLUEPRINT` + the capability flags + `STARTABLE_TYPES` + label
- `TestSelector.blueprint`, `poolSize`, `preview`, and
  `SelectionPreview.sections` (tree)
- `TestReportBuilder` blueprint tree card
- tests: `BlueprintQuotasTest`, `BlueprintSelectorTest`, `TestPlanTest`,
  `TestReportBuilderTest`, `BlueprintTreeTest`

Acceptance: pure tests prove recursive quotas, determinism, composition and plan
validation; nothing is reachable from the UI yet.

### PR 2 — Storage, repository, backup

- entities (`blueprint_sections.parentSectionId`), `BlueprintDao`,
  `MIGRATION_24_25`, schema JSON
- repository API, `blueprintSectionViews` (one category-count query + Kotlin
  subtree roll-up), `buildPlanBlueprint` (tree → expanded terminal scopes)
- `study.json` writer/reader/sink, restore (tree shape)
- `MigrationTest`, `BlueprintDbTest`, backup tests
- the existing-path guard (§11.2)

Acceptance: a recursive blueprint can be created and read in a test; it
survives a backup/restore; the migration opens cleanly from v24 and v23.

### PR 3 — Setup sheet and editor

- `BlueprintEditorSheet` (recursive tree editor, local + global share)
- `TestSetupSheet` picker, section tree preview, scope-ownership rule
  (`isCustom` gains `BLUEPRINT`; `available = achievable`)
- `TestSetup.blueprint`
- `TestViewModel.buildPlan` (variant branch; loads and freezes the blueprint
  tree, expands each terminal)
- `TestSnapshot` needs no change (variant is compared generically)
- `LibraryViewModel.previewPlan` (builds the same `PlanBlueprint`)
- `testRoute` caller
- strings
- instrumented `BlueprintPathsUiTest`

Acceptance: three blueprints over one paper can be created, picked and run;
the preview shows the resolved section tree and any shortfall.

### PR 4 — Comparability and the Tests tab

- `ExamComparisonKey` + `variant`
- `ExamStats.label`, `TestProgressData.exams` per profile,
  `McqRepository.buildExamStats`
- `ExamSection` profile chips and tree roll-up of category bars
- `ResultsScreen`: the blueprint tree card above the topics card
- `ExamStatsTest`, `StatsAggregatorTest`/`StatsTabsStreamTest` adjustments

Acceptance: UPSC, SSC and State PSC each have their own trend; subdividing a
blueprint without changing its union does not split the trend; a v24 Random
attempt keeps its group; the tab shows the profile label and its sections.

Estimated total: **L**. PR 3 is the largest (the tree editor); PR 1 is the
riskiest (it changes `TestPlan` and `Composition`, which the whole Test
feature shares, and the recursive allocation is the core math).

## 13. Whole-feature acceptance

- [ ] Three named blueprints can exist on one paper, each with its own weights,
      at any depth of the category tree.
- [ ] A blueprint run asks each terminal its quota, fills a shortfall only from
      the blueprint's own subtrees, and records the requested and actual counts
      at every level of the tree.
- [ ] The setup preview shows the section tree, the achievable count and any
      unavailable subtree **before** Start.
- [ ] A blueprint attempt is representative: it feeds `rep*` and the mock trend.
- [ ] Two blueprints over the same categories and length never share a trend; a
      v24 attempt's group is unchanged.
- [ ] The report shows the blueprint's sections as a tree with `asked / requested`
      and a small-sample note below 5 asked.
- [ ] The Tests tab offers the profiles present in the selected group, labelled
      from the attempt's composition, and still works after the blueprint is
      deleted.
- [ ] A blueprint (including its subsection tree) survives a backup/restore with
      its ids; a v1 `study.json` restores without it.
- [ ] Deleting a category removes its section and its whole subsection tree;
      moving a category to another paper leaves the row and reports it as
      unavailable. Either way the blueprint keeps working with the remaining
      sections.
- [ ] `:app:testDebugUnitTest` and `:app:lintDebug` pass; Robolectric and
      instrumented tests pass on CI x86_64.

## 14. Non-degradation guarantees

The feature is additive only. Every change below is a new field, a new row, or
a new branch — no existing branch is redefined.

| Existing functionality | What the blueprint changes | Guarantee |
|---|---|---|
| `TestType` Random/Balanced and every practice type | Adds one enum value `BLUEPRINT`; `isRepresentative` becomes `RANDOM \|\| BALANCED \|\| BLUEPRINT` | Every other `TestType` branch has unchanged semantics. `TestType.fromStorage("BLUEPRINT")` resolves; unknown names still return null |
| `TestPlan.validationError` / `resolve` | Adds `blueprint: PlanBlueprint? = null` (default null) | A non-blueprint plan is byte-identical to today; `validationError(available)` is unchanged for every `available` in 0..200 (test, §11.1). `resolve()` is untouched for every other type |
| `TestPlan` scope/`scopeKey` rules | Reuses the existing `ScopeKey` and `CategorySelection.orderedIds` | No scope-key algorithm change; whole-paper vs scoped keys keep their meaning |
| `Composition` (all types) | `version 1 → 2`, new `label`/`sections` with defaults | v1 rows still parse (guard widened, §4.3); `Composition.of(...)` is unchanged; an older app reading a v2 row returns null (existing fallback), which every caller tolerates |
| `TestReport` for every non-blueprint attempt | Adds `val blueprint: BlueprintReport? = null` | `blueprint == null` for Random/Balanced/every practice type; every existing report field, `topics`, `score`, `marginPoints` is computed identically |
| `ExamComparisonKey` / Exam trend | Adds `variant: String?` (default null) | Every existing representative attempt already has `variant == null`, so every existing key is unchanged. Asserted in `ExamStatsTest` for a v24 Random attempt |
| `rep*` counters | `applyQuestionStats` already reads `isRepresentative` | Blueprint widens the representative set by one type only; Random/Balanced `rep*` behaviour is unchanged, and the widen-by-one is asserted separately |
| `question_stats` schema | No change | No column added, no recompute rule changed |
| Fresh-sample avoidance (`RECENT_3`) | `representativeNames()` now includes `BLUEPRINT` | A recent blueprint run counts as recent practice for avoidance — intended, and it only ever *excludes* more, never less |
| Fixed panels, Tags, Coverage, etc. | No change to their `variant` or selector paths | Their `variant` checks stay as-is; `BLUEPRINT` is a fifth accepted variant, not a reinterpretation of the existing four |
| `TestSnapshot` / resume | No schema change; `matches(...)` already compares `variant` generically | A non-blueprint snapshot is identical; a blueprint snapshot resumes from its frozen `selectedQuestionIds` and never re-reads weights |
| Backup / restore (`study.json`) | Adds optional `blueprints` array | `StudyFile` is lenient (`ignoreUnknownKeys = true`); a backup without `blueprints` restores as empty. No `FORMAT.VERSION` bump |
| Import/export (`content.json`, Anki) | No change | Blueprints are device state only, like panels; attempts' `compositionJson`/`variant` round-trip unchanged |
| SRS / Statistics tabs | No accumulator or streaming change | `TestProgressData.exams` is built once in `McqRepository.buildExamStats` (pure, not streamed — §8.3); the plan's "two builds, one accumulator" rule is not touched |
| Database | v24 → v25, **additive only** | Two new tables, two new indexes, no column dropped or retyped, no data rewrite. `MigrationTest` proves v23 → v25 and v24 → v25 open with Room's identity hash |
| UI (Library, Session, Results, Statistics) | Adds one chip, one editor, two cards | The new chip is the only entry point; no existing chip is relabelled or reordered. `testTypeLabelRes` gains a `BLUEPRINT` branch explicitly — without it both label helpers fall through to `RANDOM` and the feature would mislabel itself (§5.3) |
| Tests | New tests only, plus the guards in §11.4 | Full existing suites (`:app:testDebugUnitTest`, `:app:lintDebug`, CI Robolectric/instrumented) must stay green per PR; PR1 lands alone so the shared-class regression is isolated |

Three rules the implementation must not break:

1. **No silent fallback.** A missing blueprint, an empty blueprint, or a
   blueprint whose sections are all unavailable is refused. The selector never
   falls back to Random or to the whole paper.
2. **No reinterpretation of existing fields.** `variant` keeps its meaning for
   TAG / AUTHORED_DIFFICULTY / COVERAGE / FIXED_PANEL; `scopeKey` keeps its
   algorithm; `Composition` keeps reading v1.
3. **No hidden migration.** If a query proves it needs a column or index, it
   gets its own migration + schema JSON + `MigrationTest` proof. Nothing is
   smuggled into the v25 migration speculatively.

## 15. Risks and constraints

| Risk | Mitigation |
|---|---|
| `TestPlan` and `Composition` are shared by every test type; a mistake breaks all of them | Land PR 1 alone with the full existing suite as the gate; the new fields default to null/empty, so a non-blueprint plan and composition are unchanged, and §11.1 contains an `available` 0..200 regression guard |
| Deep trees make per-section figures meaningless | `asked < 5` → small-sample note at any depth; per-node margins are published in the report; the profile KPI still shows the whole-attempt margin. The editor warns when a node's quota at the chosen length falls below 5 |
| Two leaf categories share a title under different parents | Prefer current leaf id over title when the question resolves (§8.4); otherwise the rows count as unresolved rather than guessed. The editor does not block duplicate titles (the bank allows them) |
| Overlap: a parent section plus a descendant section | Validation refuses overlapping siblings/top-levels (`SECTION_OVERLAP_MESSAGE`); the tree build adds missing ancestors so the user cannot create the overlap by selecting a child alone |
| Blueprint id on `variant` collides with the custom-type meaning | `variant` is only accepted for TAG, AUTHORED_DIFFICULTY, COVERAGE, FIXED_PANEL and now BLUEPRINT; validation refuses the rest |
| A blueprint drifts as the bank changes | Sections follow the tree (decision #12); the composition records what each attempt actually asked, so history stays readable; a changed union splits the trend, which the report states |
| A `Composition` written by a newer app is unreadable here | `fromJson` returns null on an unknown version (existing behaviour); callers already tolerate a null composition |
| Per-section figures look stronger than they are | `asked < 5` → small-sample note; per-node margins are published in the report; the profile KPI still shows the whole-attempt margin |
| Extra aggregate queries in the Tests tab | At most 8 profiles per group, each on the existing chunked aggregate queries; the lifetime counters are shared |
| Migration risk | One additive migration, proven from v23 and v24 in `MigrationTest`; no data rewrite |
| `minSdk = 26` | No window functions in any new query; subtree pools are computed from category nodes + one grouped count, not from a recursive CTE |
| Blueprint import/export | Deliberately device state only; an imported attempt's label still reads from its composition |

**Measurement honesty.** A 100-question blueprint over five top-level sections
is ±20–25 points per section on one run (at p ≈ 0.5), while the whole score is
±9–10 points. Going one level down halves the sample again (a 10-question
sub-section is ±31 points; after three attempts, ±18). The section tree is a
diagnostic, not a verdict: it needs about three comparable blueprint attempts
before a subject claim is worth acting on. The UI must say so rather than rank
subjects on one run, especially at depth ≥ 2.

## 16. Out of scope

- **Per-section timing** and any per-section clock (session state machine).
- **Scoring weights** (weighted marks, per-section negative marking).
- **Blueprint in `content.json`/Anki export** — device state only, like panels.
- **Blueprint sharing/presets** across devices.
- **Section-level manuals** ("History is 20% but Ancient India at least 5%" —
  that is the category tree's job, not the blueprint's).
- **Negative-marking per section** (the paper's rate still applies to the whole
  attempt).
- **Automatic rebalancing** when a section's pool changes (the blueprint
  follows the bank; it does not rewrite itself).

## 17. Owner inputs needed before PR 3

1. The three exam names (they become the blueprint names).
2. The weight of each subject per exam, **including at least one nested
   example** (e.g. Science & Tech 30% → Physics 20, Chemistry 6, Biology 4) so
   the tree editor's local/global display can be validated against real numbers.
3. One target length per exam (the same 100 for all three is acceptable).
4. Confirmation that a deleted category should drop its whole blueprint subtree
   (rather than blocking the deletion).

## Appendix A. Worked examples

**Flat (the degenerate tree).** UPSC, 100 questions, parts
`History 2 / Geography 1 / Economics 1 / Polity 3 / Current affairs 3` →
shares 20% / 10% / 10% / 30% / 30% → quotas `20 / 10 / 10 / 30 / 30`.

**Recursive.** Parts tree (local shares in parentheses):

```
Science & Tech (30 parts of 100) = 30 questions
 ├─ Physics (2 of 5) = 12
 │   ├─ Mechanics (3 of 5) = 7
 │   └─ Optics (2 of 5)    = 5
 ├─ Chemistry (2 of 5) = 12
 └─ Biology (1 of 5)   = 6
```

Global shares: Physics 12%, Mechanics 7.2%, Optics 4.8%, Chemistry 12%,
Biology 6%. Note how quickly a legal, sensible tree produces 5-question
leaves on a 100-question mock — that is why §15 tells the editor to warn
below 5.

**Shortfall, level by level.** If History's pool has only 14 questions, History's
`pool` covers its floor (10) plus 4 of its 10 remainder slots. The
6-question deficit goes to the top-level siblings with room, ordered by
remainder then `parts`, so at most one more per pass: `achievable = 100`, the
run is complete, and the composition records History `requested=20`, asked 14.

If Physics is thin (12 asked of 14 quotas) and Chemistry/Biology have room,
Physics' 2-question deficit redistributes **inside Science** first; only a
deficit no Science child can take bubbles up to Polity/History/etc.

**Union short.** If the whole blueprint union has only 87 questions:
`achievable = 87`, `resolve()` runs 87, `clamped = true`, the setup sheet says
"Only 87 questions in this blueprint"; below 20 the run is `SHORT` and never
enters the mock trend.

Margins (95%, at p ≈ 0.5, no finite-population correction):

| Node quota | ± points | n after 3 attempts | ± points |
|---|---|---|---|
| 30 | 18 | 90 | 10 |
| 20 | 22 | 60 | 13 |
| 10 | 31 | 30 | 18 |
| 5 | 44 | 15 | 25 |
| whole 100 | 10 | 300 | 6 |

That table is the case for "report the tree, trust the whole": one run can
rank the whole exam, but a subject ranking needs three, and a sub-sub-section
ranking needs many more.
