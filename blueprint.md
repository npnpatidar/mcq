# Blueprint question selection and counting

Status: describes the code as it is implemented on `main` at `92d7dfa`
(2026-11-11). This is **not** the proposal in `exam_blueprint.md`; where the
code and that proposal differ, this document follows the code.

Primary sources:

| Concern | Code |
|---|---|
| Tree model, terminals, union | `domain/Blueprint.kt` |
| Quotas, pools, supply, demand, shares | `domain/BlueprintQuotas.kt` |
| Section view, selection, passages, fill, preview | `domain/signals/TestSelector.kt` |
| Length rules, resolve, clamp, overshoot limit | `domain/TestPlan.kt` |
| Setup-sheet availability, sampler pre-check | `domain/TestSetup.kt` |
| Repository pool counts and plan-blueprint build | `data/repository/McqRepository.kt` |
| Setup sheet / preview rows | `ui/library/TestSetupSheet.kt`, `ui/library/LibraryViewModel.kt` |
| Start / frozen selection | `ui/test/TestViewModel.kt` |

---

## 0. One-paragraph summary

A blueprint is a tree of weighted sections. Only the **terminals** (sections
with no blueprint children) sample questions: each terminal owns an expanded
set of paper categories (`scopeCategoryIds`). A request of *N* is split
recursively by each sibling group's `parts`, using floors, largest-remainder
leftovers and a minimum of one question per section where the run is long
enough. The result is a quota per terminal. The selector then takes questions
from each terminal's pool, **whole passages at a time**, interleaves the
terminals so topics are mixed, and tops up from inside the blueprint union only
if the run is still short. A passage can make a terminal, and therefore the
whole run, longer than its quota or request. The setup preview shows the
deterministic quotas plus the actual preview selection; Start rebuilds the plan
with a fresh question seed and runs the selected ids.

---

## 1. Vocabulary

| Term | Meaning |
|---|---|
| **Section** | One node of the blueprint tree: a `categoryId`, a relative `parts` weight, optional `children`, and (on a terminal) `scopeCategoryIds`. |
| **Terminal** | A section with no blueprint children. Only terminals receive quotas and sample questions. |
| **`parts`** | Relative weight **within one sibling group**. They are not percentages and are not global. `Physics 2 / Chemistry 1 / Biology 1` means 50% / 25% / 25% *of their parent*. |
| **Scope / `scopeCategoryIds`** | The terminal's expanded category subtree: the category itself plus every descendant. A question belongs to a terminal if its `categoryId` is in this set. |
| **Union** | The distinct union of all terminals' scopes (`PlanBlueprint.union`). The run never selects outside it. |
| **Pool** | Number of questions a node can draw from. Terminal pool = questions in its scope; internal pool = sum of its children's pools. |
| **Supply** | Sum of the eligible terminals' pools, independent of the request. |
| **Quota** | Questions allocated to a terminal by the recursive allocation for this request. |
| **Achievable** | Sum of the quotas at this request = `min(request, supply)` when the tree is valid. |
| **Demand** | The quota a terminal *would* want if its pool were unlimited. Used to show "short by". |
| **Requested** | The length the learner asked for (`plan.count`, 20..150 step 10). |
| **Actual / shown** | Questions actually selected and presented. Can exceed the quota and the request because passages are indivisible. |

Key relationships:

```
pool(terminal)   = count of questions whose category is in scopeCategoryIds
pool(internal)   = sum(pool of its children)
supply           = sum(pool of eligible terminals)
achievable       = sum(perTerminal quotas)        // min(count, supply)
complete         = achievable >= requested
shown            = sum over terminals of whole passage blocks taken + fill
```

---

## 2. How questions are counted (the pool)

### 2.1 Repository view of the blueprint

`McqRepository.blueprintSectionViews` loads the paper's categories and one
grouped question count per category, then computes each category's **subtree**
count post-order:

```text
pool(category) = directQuestions(category) + pool(each child category)
```

A terminal view's `pool` is that subtree count. An internal view's `pool` is
the sum of its children's view pools (it does **not** add the internal
category's own direct questions).

`McqRepository.buildPlanBlueprint` then:

1. drops every node whose category was moved to another paper
   (`view.available == false`, and therefore its whole subtree);
2. expands each remaining terminal with
   `CategorySelection.orderedIds(nodes, listOf(categoryId))`;
3. returns a `PlanBlueprint` whose `categoryIds` is the union of those
   expansions, or `null` when nothing is available.

So the frozen plan contains only terminals that still exist in this paper.

### 2.2 Selector view

`TestSelector.viewOf` rebuilds the same view tree from the live `Signals`:

```text
pool(terminal) = signals.count { it.categoryId in scopeCategoryIds }
pool(internal) = sum(children.pool)
```

`available` is always `true` here because `buildPlanBlueprint` already dropped
unavailable nodes. `BlueprintQuotas.supplyOf` still skips any
`available == false` node (the pure unit tests construct those directly).

### 2.3 Passages in the pool

A passage is not one pool unit. Each member is a normal question row and a
normal `Signals` entry, so:

- a 4-question comprehension set contributes **4** to its terminal's pool and
  **4** to the supply;
- the pool for a category containing that passage counts all 4;
- the blueprint's percentages count those 4 questions when they are selected.

What is indivisible is the **selection**, not the count. See §5.

### 2.4 What an internal section's own questions do

If a blueprint section has children, its direct questions (questions assigned
to that branch category itself, not to a child) are **not** sampled. Only the
children's scopes are sampled. This is deliberate: an internal section is a
weight splitter, not a sampler.

---

## 3. Quota allocation (`BlueprintQuotas.allocate`)

### 3.1 Inputs and outputs

```kotlin
allocate(sections: List<BlueprintSectionView>, count: Int, seed: Long = 0L)
```

- `perTerminal`: terminal category id -> quota;
- `achievable`: sum of quotas (what this request can produce now);
- `requested`: the sanitised request;
- `supply`: the count-independent sum of terminal pools;
- `complete`: `achievable >= requested`.

The selector calls it with `seed = 0` on purpose: quotas must not depend on the
question seed, so the setup preview and the run always agree on quotas. The
question shuffle later uses `plan.seed`.

### 3.2 The per-sibling-group algorithm (`allocateLevel`)

At each sibling group, with `count` questions to distribute:

1. **Eligible views.** Keep views with `available == true`, `parts > 0` and
   `pool > 0`. If none, this group gets nothing.
2. **Exact share.** For each eligible view:
   `exact_i = count * parts_i / totalParts`.
3. **Capped floor.** `assigned_i = min(floor(exact_i), pool_i)`.
4. **Short run.** If `count < number of eligible views`, do not use the floor
   shares. Rank by `(parts desc, pool desc, seeded tiebreak)`, give exactly **1**
   to the top `count` views and 0 to the rest. A short run follows the exam's
   emphasis rather than a proportional split.
5. **Leftovers.** Otherwise, `left = count - sum(assigned)`. Distribute one at a
   time, in passes, to views that still have room, ordered by
   `(fractional remainder desc, parts desc, pool desc, categoryId asc)`.
   Each view gets at most one extra per pass, so rounding spreads out instead
   of piling onto the largest.
6. **Minimum one per listed section.** When `count >= eligible.size`, any
   eligible view still at 0 is seeded to 1. If the total is already `count`,
   that 1 is stolen from the largest donor that has more than 1; otherwise it is
   added. Then any remaining deficit (created by pools or caps) is filled again
   in the same remainder order while room exists.
7. **Recurse.** For a view with children, call `allocateLevel(children, assigned_i,
   seed)`. For a terminal, `assigned_i` is its final quota.

The recursion is what makes the totals honest: an internal view's `pool` is the
sum of its children's pools, so the parent can never allocate a child more than
its subtree can supply.

### 3.3 Example A — flat, exact

UPSC, 100 questions:

| Section | parts | share | quota |
|---|---:|---:|---:|
| History | 2 | 20% | 20 |
| Geography | 1 | 10% | 10 |
| Economics | 1 | 10% | 10 |
| Polity | 3 | 30% | 30 |
| Current affairs | 3 | 30% | 30 |

All exact shares are integers, so no rounding is needed. `achievable = 100`.

### 3.4 Example B — largest remainder and tie-break

Three sections, each `parts = 1`, request 10:

```text
exact = 10/3 = 3.333 each
floors = 3, 3, 3          sum 9
left = 1
remainders = .333, .333, .333
```

The one leftover goes to the smallest `categoryId` (`a` in the test:
`a=4, b=3, c=3`). Ties are broken by id, not by view order. The displayed
percentages are likewise apportioned by largest remainder, so they sum to
exactly 100%: `40 / 30 / 30`.

### 3.5 Example C — nested tree

Top level: Science `parts = 3`, Polity `parts = 7`, request 100.

```text
Science = 100 * 3/10 = 30
Polity  = 100 * 7/10 = 70
```

Inside Science: Physics `parts = 2`, Chemistry `1`, Biology `1`, total 4,
count 30:

```text
exact      = 15, 7.5, 7.5
floors     = 15, 7, 7        sum 29
left       = 1
remainders = 0, .5, .5
tie        = "biology" < "chemistry"
```

Final terminal quotas:

| Terminal | quota |
|---|---:|
| Physics | 15 |
| Biology | 8 |
| Chemistry | 7 |
| Polity | 70 |

Global shares are the product down the path: Physics 15%, Biology 8%,
Chemistry 7%, Polity 70%. Note that the local split changed the global shares;
`parts` are not global percentages.

### 3.6 Example D — short run (fewer questions than sections)

Five sections with `parts = 5, 4, 3, 2, 1`, request 3:

- `count (3) < eligible sections (5)`, so the floor shares are ignored;
- the top three by `parts` each get exactly 1;
- the two smallest sections get 0.

If the top section is internal, its 1 is recursed into its children; with
`count = 1` at that child level, the child with the largest `parts` gets the
single question. A three-question run therefore drills one question into each
of the three most heavily weighted branches.

### 3.7 Example E — a thin sibling is exhausted, the surplus moves sideways

Sections: `thin parts=2, pool=2`; `b parts=1, pool=100`; `c parts=1, pool=100`;
request 20.

```text
exact  = thin 10, b 5, c 5
floors = thin 2 (capped), b 5, c 5   sum 12
left   = 8
```

No fractional remainders remain, so the order is `parts desc` (thin first, but
it is full), then `b`/`c`. One per pass:

```text
b 6, c 6
b 7, c 7
b 8, c 8
b 9, c 9
```

Quotas: `thin 2, b 9, c 9`. The run is complete at 20. `thin`'s demand was 10,
so the UI would show it short by 8.

### 3.8 Example F — a thin child redistributes inside its parent first

Top level: Science `parts=1` (children Physics 2/pool 5, Chemistry 1/pool 100,
Biology 1/pool 100), Polity `parts=1`/pool 100; request 40.

```text
Top: Science 20, Polity 20
Science (count 20):
  exact  = Physics 10, Chemistry 5, Biology 5
  floors = Physics 5 (capped), Chemistry 5, Biology 5   sum 15
  left   = 5
  order  = Physics (parts 2) is full, then Biology/Chemistry
```

One per pass after the cap:

```text
Biology 6, Chemistry 6
Biology 7, Chemistry 7
Biology 8
```

Science terminals: Physics 5, Biology 8, Chemistry 7 (= 20). Polity 20.
The 5-question deficit never leaves Science because Chemistry and Biology had
room inside it. Only when the whole Science subtree is short does the parent's
pool cap pull Science's allocation down and give the surplus to Polity.

### 3.9 Example G — unavailable subtree

An unavailable node contributes 0 to supply and is filtered out of the eligible
group; its siblings are allocated as if it were not listed. It never consumes a
quota and never blocks a sibling. In the real app, an unavailable node has
already been dropped by `buildPlanBlueprint`, so this path is mainly a pure-test
and defensive path.

### 3.10 `supply` vs `achievable`

`supply` is independent of the request; `achievable` is capped by it.

Example: `thin pool=2`, `b pool=100`, `c pool=100`:

| Request | supply | achievable | quotas |
|---:|---:|---:|---|
| 0 | 202 | 0 | empty |
| 20 | 202 | 20 | thin 2 (capped), b 9, c 9 |
| 100 | 202 | 100 | proportionally larger |
| 250 | 202 | 202 | the whole union |

The setup sheet uses `supply` as `available` so the length control can grow,
and the preview's `pool` is `achievable` at the currently previewed request.

### 3.11 `demand` (the "short by" figure)

`demand` runs the same allocation with every pool replaced by `Int.MAX_VALUE`.
It answers "what would this section ask for before its bank ran out?" The UI
shows `demand - awarded` only when positive. `demand` is an ideal, not a lower
bound: redistribution can legitimately award a section **more** than its demand
when a sibling is thin (Example E: `b` and `c` receive 9 against a demand of 5).

### 3.12 Determinism

- Same `(sections, count, seed)` -> same allocation.
- The seed only breaks exact ties in a short run.
- The selector always passes `seed = 0`, so quotas are independent of the
  question seed and the preview/run quota comparison is stable.

---

## 4. Turning quotas into selected questions (`TestSelector.blueprint`)

### 4.1 Pipeline

```text
build view tree from the frozen PlanBlueprint
  -> allocate quotas (seed 0)
  -> for each terminal with quota > 0:
         take whole passages from its scope until the quota is met
  -> interleave the terminals question by question
  -> if still below plan.count, fill inside the blueprint union
  -> ids + Composition(label, section tree, segments)
```

### 4.2 One terminal's take

For a terminal with quota `need`:

```kotlin
val scope = terminal.scopeCategoryIds.toSet()
val candidates = signals.filter { it.categoryId in scope }
takeWholePassages(candidates, target = need, seed = plan.seed xor terminalId.hashCode())
```

Without passages this is effectively a deterministic shuffle followed by
`take(need)`. With passages it is block-aware (§4.3).

### 4.3 Whole passages

Passage grouping key:

```text
"p:<passageId>" for a passage member
"q:<questionId>" for a standalone question
```

`takeWholePassages(candidates, target, seed)`:

1. shuffle the individual candidates with `Random(seed)`;
2. group them by passage key, preserving first appearance order;
3. walk the groups and add **every member** of a group;
4. stop when the accumulated count is `>= target`.

Consequences:

- a terminal's actual count can be `target + (lastBlockSize - 1)`;
- a passage is never split *within that terminal's candidate list*;
- if the first block is larger than the target, it alone can overshoot;
- the chosen block depends on the seed.

### 4.4 Interleave

`interleave(perTerminal)` round-robins the terminal lists:

```text
A = [a1, a2, a3], B = [b1, b2]
=> a1, b1, a2, b2, a3
```

It mixes topics and preserves each terminal's internal order. It does not
regroup passages; passage membership is already fixed.

### 4.5 Fill

`fillWithWholePassages(selected, target = plan.count, pool = inScope,
stages = [BLUEPRINT_FILL -> inScope])`:

1. if the selected count is already `>= target`, stop;
2. for each stage, group the candidates by passage (in raw signal order, not
   shuffled), and add every member not already taken, one block at a time,
   until `target` is reached;
3. if still short, repeat with the whole union shuffled by `seed xor 7`, and
   record that as `RANDOM`.

A group already partly present is **completed** (`fresh = group.filter {
taken.add(id) }`) rather than extended one question at a time.

Given the allocation invariant `achievable = min(count, supply)`, the quota
phase already reaches `min(count, supply)`, so the fill stages normally add
nothing. They are a defensive path (and a safety net if a selector ever leaves
a gap).

### 4.6 Composition

`Composition` records:

- `label` — the blueprint name;
- `sections` — a tree mirroring the blueprint, where:
  - a terminal's `requested` is its quota;
  - an internal node's `requested` is the sum of its children;
- `segments` — `BLUEPRINT` with the number of questions selected in the quota
  phase (this is the **actual** interleaved size, including passage
  overshoot), plus any `BLUEPRINT_FILL`/`RANDOM` questions.

So `sum(sections[*].requested)` is the quota plan (usually `plan.count`), while
`segments[BLUEPRINT].count` is what the quota phase actually took. They differ
when a passage overshoots. The report derives per-section `asked` from the
stored results, not from `requested`.

### 4.7 Preview

`TestSelector.preview(plan, signals)`:

- runs the real selection to get `ids` and `terminalCounts`;
- separately allocates at `plan.count` to get the `sections` preview and
  `pool = achievable`;
- `capacity = supply`;
- for a blueprint, `sections[*].requested` are the quotas;
- `terminalCounts` are the **actual** counts after whole passages.

The setup sheet:

- uses `available = capacity` (supply) so length chips can grow;
- uses `maxRequestForBlueprint(supply)` (round up to the next step of ten,
  capped at 150);
- prints the section rows from the preview `actual` counts (falling back to
  quotas), with percentages computed from those counts;
- prints "short by X" using `demand - awarded`.

### 4.8 Resolve and Start

- The requested length must be a normal blueprint length: 20..150 step 10
  (`TestPlan.validationError`). The **resolved** count is not rounded.
- `TestPlan.resolve` for `BLUEPRINT`:
  `count = min(request, available)`, `clamped = request > available`,
  `short = available < 20`.
  A 99-question supply with a 100 request runs 99; an 87-question supply with a
  90 request runs 87; below 20 it is `SHORT` and does not enter the mock trend.
- The setup sheet passes `available = supply`. Because
  `achievable = min(request, supply)`, this is consistent with clamping to the
  quota sum.
- Start (`TestViewModel.buildPlan`) rebuilds the `PlanBlueprint` from the
  repository (dropping unavailable sections), assigns a **new random seed**
  (`Random.nextLong()`), and selects again. The setup's preview ids are display
  only; the run's ids are the frozen selection for that new seed.

---

## 5. Passages: every scenario

### 5.1 The rule

A passage is a block: all members share one `passageId` and one category, and a
selection either takes all members present in the candidate list or none. The
unit of counting is still the question: each member adds 1 to the pool and 1 to
the run.

### 5.2 Scenario table

| # | Scenario | What happens | Example |
|---|---|---|---|
| 1 | No passages | deterministic shuffle, `take(need)` | quota 5, pool 20 -> 5 |
| 2 | Passage fits under the quota | passage plus other blocks until quota | quota 6, passage 4 + standalones -> 6 |
| 3 | Passage exactly the quota | passage only | quota 4, passage 4 -> 4 |
| 4 | Passage larger than the quota | whole passage, overshoot | quota 2, 4-member passage -> 4 |
| 5 | Several passages | blocks in seeded order until quota met; last may overshoot | quota 2, blocks 4 and 4 -> 4 |
| 6 | Quota larger than the pool | allocation already capped the quota at the pool; the terminal takes everything | pool 3, quota 10 -> 3 |
| 7 | Short run (`count < sections`) | top sections get 1 each; a 1-question terminal with a 4-member passage still takes 4 | request 3 -> actual could be 6+ |
| 8 | Several terminals with passages | each terminal overshoots its own quota; overshoots add up | quotas 10 + 10, blocks 15 + 12 -> 27 |
| 9 | Whole union short | allocation reaches the union supply; fill finds nothing | supply 12, request 20 -> 12 |
| 10 | All questions are one passage | one block is the whole run | request 20, 60-member passage -> 60 |
| 11 | Passage seed changes | the block order, and therefore which passage appears, changes | preview seed vs run seed |
| 12 | Fresh-sample exclusion | any recent member excludes the whole passage before selection | a 4-member passage with 1 recent member is gone |

### 5.3 Membership vs adjacency

The guarantee is **membership**: a selected passage is not partially selected.
The interleave step can place its members apart in the presented order
(`a1, b1, a2, b2, ...`). If question shuffle is enabled, `SessionOrder` groups
passage blocks while shuffling; if shuffle is disabled, the interleaved order is
kept. That is an ordering detail, not a split: every member is still in the
run.

### 5.4 Preview counts and quota counts differ on purpose

For a terminal with quota 3 and a 4-member passage as the first block:

```text
allocation quota (composition/section row) = 3
actual terminal count (preview terminalCounts, run) = 4
```

The preview's section row prints the **actual** count when it is available, so
the learner sees 4, while the "quota plan" still records 3.

---

## 6. End-to-end worked example (nested + passage + percentages)

Blueprint "UPSC", request 20:

| Section | parts | scope | pool | notes |
|---|---:|---|---:|---|
| Polity | 3 | polity | 10 | 6 standalone + a 4-member passage P1 |
| History | 2 | history | 5 | 5 standalone |
| Science & Tech | 5 | — | 13 | internal |
| — Physics | 2 | physics | 6 | standalone |
| — Chemistry | 1 | chemistry | 4 | one 4-member passage C1 |
| — Biology | 1 | biology | 3 | standalone |

### 6.1 Quotas

Top level total `parts = 3 + 2 + 5 = 10`, request 20:

```text
Polity  = 20 * 3/10 = 6
History = 20 * 2/10 = 4
Science = 20 * 5/10 = 10
```

Inside Science, total `parts = 2 + 1 + 1 = 4`, count 10:

```text
exact      = Physics 5, Chemistry 2.5, Biology 2.5
floors     = 5, 2, 2          sum 9
left       = 1
remainders = 0, .5, .5
tie        = "biology" < "chemistry"
```

Final quotas: Polity 6, History 4, Physics 5, Chemistry 3? No: Biology gets
the leftover, so **Chemistry 2, Biology 3**. Sum = 6 + 4 + 5 + 2 + 3 = 20.

### 6.2 Actual selection

- Polity quota 6, pool 10 with P1 (4) and 6 standalone: whichever block order
  the seed produces, the terminal reaches exactly 6 (P1 + 2, or 6 standalone).
  `actual = 6`.
- History quota 4, 5 standalone: `actual = 4`.
- Physics quota 5, pool 6: `actual = 5`.
- Chemistry quota 2, pool is the single 4-member passage C1: the whole block is
  taken, `actual = 4` (overshoot by 2).
- Biology quota 3, pool 3: `actual = 3`.

Total actual = `6 + 4 + 5 + 4 + 3 = 22`. Since 22 >= 20, the fill stage does
nothing.

### 6.3 What each number means

| Number | Value |
|---|---|
| Requested | 20 |
| Quota plan (composition `requested`) | 6 / 4 / 5 / 2 / 3 = 20 |
| Actual shown (composition `BLUEPRINT` segment) | 22 |
| Preview `terminalCounts` | Polity 6, History 4, Physics 5, Chemistry 4, Biology 3 |
| Preview `pool` / `capacity` | 20 / 32 (supply = 10 + 5 + 6 + 4 + 3 = 28? see below) |

Supply in this example is `10 + 5 + 6 + 4 + 3 = 28`. At request 20,
`achievable = 20`; `capacity = 28`.

### 6.4 Percentages

Quota shares (deterministic, used by the editor):

```text
6/20 = 30%, 4/20 = 20%, 5/20 = 25%, 2/20 = 10%, 3/20 = 15%
```

Actual shares (used by the setup preview when `terminalCounts` are present):

```text
6/22 = 27.27 -> 27
4/22 = 18.18 -> 18
5/22 = 22.73 -> 22 + 1 (largest remainder) = 23
4/22 = 18.18 -> 18
3/22 = 13.64 -> 13 + 1 (largest remainder) = 14
```

The largest-remainder pass makes the five rows sum to 100%:
`27 / 18 / 23 / 18 / 14`. An internal row is the sum of its children, so
Science would show 23 + 18 + 14 = 55%.

### 6.5 The same tree at request 100

Quotas become Polity 30, History 20, Science 50 (Physics 25, Chemistry 12,
Biology 13 after the same rounding). Chemistry still takes its whole 4-member
passage, so the overshoot matters much less in percentage terms. This is why a
deeper tree produces weak per-section claims: the quotas get small quickly.

---

## 7. Edge cases and scenario catalogue

1. **Request 0 or negative.** `allocate` returns empty quotas, `achievable = 0`,
   but still reports `supply`. The UI never starts such a run.
2. **Blueprint with no sections.** `TestPlan.validationError` refuses
   (`SECTIONS_MESSAGE`); the selector returns an empty `Selection`.
3. **Blueprint with all sections unavailable/empty.** `buildPlanBlueprint`
   returns null; Start is refused ("no available sections"), never a fallback to
   Random or the whole paper.
4. **One terminal.** The whole request goes to it, capped by its pool. A
   passage can still overshoot.
5. **`count == number of sections`.** Every eligible section gets exactly one
   (the minimum-one rule and the short-run rule coincide).
6. **`count > number of sections`.** Proportional floors + remainder passes +
   minimum one; redistribution when a pool is thin.
7. **`count < number of sections`.** Only the top `count` by `parts` appear,
   one each; the seed only breaks ties.
8. **Section pool 0.** It is ineligible, gets 0, and does not block siblings.
9. **Section pool < quota.** The quota is capped at the pool; the surplus moves
   to siblings at the same level, then to the parent's level through the
   internal pool cap.
10. **Whole union short.** Every terminal is exhausted; the run is
    `min(request, supply)`; `resolve` clamps and reports "Only N questions".
    Below 20 the attempt is `SHORT` and leaves the mock trend.
11. **Moved category.** `blueprintSectionViews` marks the node (and its
    subtree) unavailable; the setup shows it struck through; the plan drops it;
    the remaining sections still run.
12. **Deleted category.** The database cascade removes the section and its
    blueprint subtree; nothing is left to mark.
13. **Fresh sample (`RECENT_3`).** Before selection, Start removes recent
    comparable questions and any passage containing one, then applies the
    filter only if the remaining pool is at least twice the request. A thin
    section can therefore go short.
14. **Determinism.** Quotas use seed 0; question order and passage choice use
    `plan.seed xor terminalId.hashCode()`. A resumed run replays frozen ids.
15. **Interleave with unequal terminal lists.** Round-robin finishes the longer
    lists after the shorter ones are exhausted.
16. **Fill never leaves the union.** `BLUEPRINT_FILL` and the last-resort
    `RANDOM` stage both use `inScope`.
17. **Empty signals.** The selector returns an empty `Selection`; it never
    throws and never falls back.

---

## 8. Bugs and gaps found in the current code

The following are behaviors I consider bugs (or at least contract violations)
against the app's own stated rules. Each has a concrete example and the
behavior I think is correct.

### Bug 1 — Blueprint selection has no global overshoot / `MAX_LENGTH` bound

**Where.** `TestSelector.takeWholePassages` and `TestSelector.blueprint`.
Every other bounded sample goes through `SessionOrder.sampledBounded`, which
enforces `TestPlan.samplerLimit(target, max)` and refuses when no block fits.
A blueprint is a signal selector, so Start freezes `focusSelection.ids` and
never calls `sampledBounded`.

**Why it is a bug.** The app's rule is that whole-passage overshoot is bounded:
shown `<= min(max, target + max(1, ceil(target/4)))`. For a blueprint, each
terminal overshoots independently, and the overshoots add up.

**Example (violates the section-9.6 limit).** Request 20, two terminals with
`parts = 1` each, quotas 10 and 10:

- terminal A's pool is one 15-question passage;
- terminal B's pool is one 12-question passage.

`takeWholePassages` takes A = 15 and B = 12, total **27**. The limit for a
20-question target is 25 (`20 + 5`), and the union's smallest block is 12, so
`samplerCanStart(20, 12)` passes and the setup allows Start. The learner asked
for 20 and gets 27.

**Example (violates the hard maximum).** Request 150, two terminals, each pool
one 100-question passage. Quotas 75 and 75; each takes its whole 100-question
block, total **200 > `TestPlan.MAX_LENGTH` (150)**. The limit for 150 is 150,
and the smallest block 100 fits it, so the pre-check allows the run.

**Correct behavior.** Bound the *total* shown count, not each terminal
separately. Either:

- allocate at block granularity across terminals (a block-aware knapsack over
  the union, like `sampledBounded`, while respecting each terminal's quota), or
- after taking blocks, if `total > samplerLimit(target)` or `total > MAX_LENGTH`,
  refuse before Start with `NO_PASSAGE_FITS` / `PASSAGE_TOO_LARGE`.

The setup sheet's Start gate and the selection must use the same check, and the
preview must print the true shown count. Per-terminal overshoot inside that
bound is fine and intended.

---

### Bug 2 — The setup's minimum-block pre-check uses the whole paper, not the blueprint union

**Where.** `TestSetupSheet` computes `minBlockSize` from
`loadMinBlockSize(type, expanded)`, where `expanded` is the expansion of the
**ticked** categories. In Blueprint mode the tick-set is cleared (the blueprint
owns its scope), so `expanded` is empty and
`McqRepository.getMinBlockSize(paperId, emptyList())` returns the smallest block
in the **whole paper** (`minBlockSizeForPaper`), not in the blueprint union.

**Why it is a bug.** The blueprint sampler only sees its union, so the union's
smallest block is the one that decides whether a run can fit. A small block
outside the union masks a huge passage inside it.

The error is **one-sided**: the whole-paper minimum is a minimum over a
superset, so it can never be *larger* than the union's. It can let a run start
that the union would refuse, never refuse one that would run.

**Example (false allow).** Paper: Polity has 30 standalone questions; History
has one 60-question passage. Blueprint "History only", request 20.

- ticked expansion is empty -> `getMinBlockSize` = 1 (Polity's standalones);
- `samplerCanStart(20, 1)` = true, so Start is enabled;
- the selector's History terminal has quota 20 and takes the whole 60-question
  passage -> **the learner asked for 20 and gets 60**, far above the 25 limit.

The same happens with a small *passage* outside the union: if the paper has no
standalone at all, `minBlockSizeForPaper` takes the smallest passage, so a
5-question passage elsewhere hides a 60-question passage in the union.

With the correct union minimum (60), `samplerCanStart(20, 60)` is false and the
setup would show "No passage fits the requested length and overshoot limit".

**Correct behavior.** Compute `minBlockSize` over the blueprint's union
(`blueprintPlan.categoryIds`, the same ids used for `getQuestionSignals`) and
use that in `TestSetup.samplerRefusal`. Also use the same union for any
"largest passage" guidance. This must cover internal terminals: the relevant
block is any passage whose category is in the union.

---

### Bug 3 — Preview actual counts can disagree with the run because the seed is chosen at Start

**Where.** `LibraryViewModel.previewSelection` builds the preview `TestPlan`
without a seed (default `0`), so `TestSelector.preview` takes passages with
seed 0. `TestViewModel.buildPlan` assigns `seed = Random.nextLong()`, and the
run takes passages with that seed. The preview's `terminalCounts` and `ids` are
printed in the setup sheet as the actual section counts.

**Why it is a bug.** Quotas are seed-independent and do agree, but actual
counts are not: which whole passage is taken, and therefore how large the
overshoot is, depends on the shuffle seed.

**Example.** One terminal, request/quota 2. Its pool is one 4-member passage P
plus two standalone questions S1, S2.

- Preview seed 0: the shuffle puts S1 and S2 first -> takes 2 standalone ->
  `terminalCounts = 2`.
- Run seed X: the shuffle puts P first -> takes the whole passage ->
  shown count 4.

The setup row says 2 (and 100% of a 2-question run); the session shows 4. The
same divergence can make a section that the preview says is short look
different in the run.

**Correct behavior.** Choose the sampling seed *before* the preview and carry
it through the route into `TestPlan.seed`, so the preview and Start use the
same seed and the ids are identical. If that is not possible, show quotas
(deterministic) as the contract and label `terminalCounts` as an estimate; do
not promise that the preview row matches the session.

---

### Bug 4 — The setup preview ignores Fresh-sample (`RECENT_3`) exclusion

**Where.** `TestSetupSheet` has `avoidRecentQuestions` and includes
`ExclusionMode.RECENT_3` in the setup plan, but `loadPreview` calls
`previewSelection(paper, categoryIds, type, count, breadth, variant)` with no
exclusion argument. `LibraryViewModel.previewSelection` does not read recent
ids. Start (`TestViewModel`) does: it computes `recentComparableQuestionIds`
for the last three comparable attempts, removes recent questions and any
passage containing one, and applies the filter only if
`freshSampleFits(filtered.size, count)`.

**Why it is a bug.** For a blueprint (Fresh is supported), the setup can show a
full supply, a 100-question capacity and full quotas, while Start runs from a
much smaller filtered pool. The displayed section counts and any "short by"
lines can be wrong, and the chosen length may be impossible.

**Example.** Blueprint supply 100. Fresh is on and the last three comparable
attempts used 60 questions across the sections. Start removes those 60 (and any
passage containing one), leaving 40. A 100 request then allocates only 40.
The preview, which saw all 100, said 100 questions and no shortfall.

**Correct behavior.** Apply the same exclusion in the preview path (compute the
recent ids, filter the signals, and pass the effective pool to
`TestSelector.preview`), or show both "full pool" and "fresh pool" figures and
gate the length on the fresh one. `available`/`capacity` should be the pool the
run will actually see.

---

### Bug 5 — Other signal selectors can split a passage (related, not blueprint-only)

**Where.** Only `TestSelector.blueprint` uses `takeWholePassages` /
`fillWithWholePassages`. `weakest`, `toughest`, `easiest`, `tricky`, `atRisk`,
`unseen`, `balanced`, `coverage`, `mixed`, `fromPool`, `authored` and `slowest`
select individual `Signals` with `take(need)` (and the non-block-aware
`fillWith`). `TestViewModel` freezes those ids and calls
`SessionOrder.idsInOrder`/`getSessionOrderForIds`, not `sampledBounded`, so no
later step repairs the membership.

**Why it is a bug.** Balanced is a representative mock beside Blueprint, and
the app's block rule says a comprehension set must not be split. A Balanced
sample can take two members of a three-member passage.

**Example.** Balanced, request 4, one category whose pool is passage P (3
members: p1, p2, p3) plus standalone S1..S5. The per-category
`shuffled(seed).take(4)` may return `p1, p2, S1, S2`; the run shows only two of
the three passage questions.

**Correct behavior.** Share the block-aware take (and fill) across every
selector that can sample passages, or add a block-aware completion pass over
the frozen focus ids. At minimum, Balanced (representative) should be
block-safe like Blueprint. If splitting is intentional for practice types,
document it and stop claiming universal block safety.

---

### Bug 6 (minor) — `poolSize(BLUEPRINT)` without a blueprint counts the whole scope

`TestSelector.poolSize`:

```kotlin
TestType.BLUEPRINT -> {
    val union = blueprint?.union ?: return signals.size
    signals.count { it.categoryId in union }
}
```

A caller that forgets the `blueprint` argument gets the whole-scope count
instead of the blueprint union. The current preview path does not hit this
(it handles blueprints separately), but the API invites a wrong "pool" figure.
The existing test passes only because its signals happen to contain nothing
outside the union. **Correct behavior:** return `0` or require the blueprint
explicitly.

---

### Not bugs (intentional behavior worth not "fixing")

- **Per-terminal overshoot.** Taking a whole passage past a quota is deliberate;
  the bug above is the missing *global* bound, not the per-terminal overshoot.
- **Actual > requested.** A blueprint run can legitimately be longer than the
  requested count because of passages.
- **Short-run emphasis.** A run shorter than the number of sections picks the
  highest-`parts` sections, one question each, instead of a proportional split.
- **Internal section's direct questions are excluded.** Only terminal scopes
  sample.
- **`demand` can be lower than the awarded quota.** Redistribution from a thin
  sibling is allowed to give a section more than its pool-free ideal.
- **Fill stages are currently dead code in normal runs.** The allocation
  invariant already reaches `min(count, supply)`, so `BLUEPRINT_FILL` and the
  last-resort `RANDOM` almost never add a question. They are defensive. If a
  future change breaks the invariant, note that `BLUEPRINT_FILL` scans raw
  signal order (not shuffled), so it would be biased toward early questions.

---

## 9. Test coverage map

| Behavior | Test |
|---|---|
| Flat proportional quotas | `BlueprintQuotasTest.flatAllocationIsProportional` |
| Largest remainder tie by id | `BlueprintQuotasTest.largestRemainderBreaksTiesByCategoryId` |
| Short run top-parts one each | `BlueprintQuotasTest.shortRunPicksTopPartsOneEach` |
| Seed only breaks ties | `BlueprintQuotasTest.shortRunSeedOnlyBreaksTies` |
| Thin sibling redistribution | `BlueprintQuotasTest.thinSectionIsExhaustedAndSurplusMovesToSiblings` |
| Nested split | `BlueprintQuotasTest.nestedScienceSplitsByRelativeParts`, `threeLevelTreeRollsUp` |
| Thin child inside parent | `BlueprintQuotasTest.thinChildRedistributesInsideParentFirst` |
| Property: sum = min(count, pools) | `BlueprintQuotasTest.sumEqualsMinOfCountAndPools` |
| Unavailable subtree | `BlueprintQuotasTest.unavailableSubtreeContributesZeroAndNeverBlocksSiblings` |
| Supply vs achievable | `BlueprintQuotasTest.supplyIsCountIndependentWhileAchievableStaysCapped`, `supplyRollsUpEligibleTerminalsAndSkipsUnavailableSubtrees` |
| Demand vs cap | `BlueprintQuotasTest.demandReportsTheQuotaBeforePoolsCapIt` |
| Shares roll up and sum to 100 | `BlueprintQuotasTest.sharesRollUpCountsAndPercentagesAcrossSubcategories`, `sharesUseQuestionsNotPartsAndSumTo100`, `passageMembersCountAsQuestionsNotAsOneBlock` |
| Terminals get quotas, union respected | `BlueprintSelectorTest.eachTerminalGetsItsQuotaAndUnionIsRespected` |
| Nested composition | `BlueprintSelectorTest.nestedBlueprintComposesTheTreeAndFillsInsideUnion` |
| Thin pool stays in union | `BlueprintSelectorTest.thinPoolFillsOnlyFromInsideTheUnionAsBlueprintFill` |
| Empty pool never throws/falls back | `BlueprintSelectorTest.emptyPoolReturnsEmptySelectionNeverThrows` |
| Composition v1/v2 | `BlueprintSelectorTest.compositionV1StillParsesAndV2RoundTrips` |
| Preview pool vs union / capacity | `BlueprintSelectorTest.previewPoolIsTheAchievableQuotaNotTheUnionSize`, `previewCarriesTheWholeSupplyNotTheRequestedQuota` |
| Preview quotas agree with run | `BlueprintSelectorTest.quotaPreviewAndRunAgreeEvenWithDifferentQuestionSeeds` |
| Passage all-or-none | `BlueprintSelectorTest.aPassageIsTakenAllOrNone` |
| Preview counts passage members | `BlueprintSelectorTest.previewCountsEveryMemberWhenAPassageOvershootsTheQuota` |
| Determinism | `BlueprintSelectorTest.selectionIsDeterministicForTheSeed` |
| Blueprint length rounding/clamp | `TestSetupTest.aBlueprintRequestRoundsUpToTheNextSelectableLength`, `aNinetyNineQuestionBlueprintRunsAtItsWholeSupplyForARequestOfOneHundred` |
| Repository tree, cascade, views | `BlueprintDbTest` |

### The six bugs in §8, pinned

Each bug has tests that assert the **correct** behaviour and are `@Ignore`d
while the bug is open, so the suite stays green without ever asserting a bug.
Un-ignore a test in the commit that fixes its bug: a pass is the proof the
behaviour changed, and a still-failing one means the fix was partial.

| Bug | Test |
|---|---|
| 1 — no global overshoot bound | `BlueprintBugsTest.bug1_twoTerminalsStayInsideTheSamplerLimit`, `bug1_threeTerminalsStayInsideTheSamplerLimit`, `bug1_shownCountNeverExceedsTheHardMaximum`, `bug1_nestedTerminalsAreBoundedAsOneRun` |
| 2 — pre-check over the whole paper | `BlueprintBugsDbTest.bug2_aStandaloneElsewhereDoesNotHideTheUnionsBigPassage`, `bug2_aSmallPassageElsewhereDoesNotHideTheUnionsBigPassage` |
| 3 — preview and run do not share a seed | `BlueprintBugsTest.bug3_oneTerminalCountIsStableAcrossRunSeeds`, `bug3_everyTerminalCountIsStableAcrossRunSeeds` |
| 4 — preview ignores Fresh exclusion | `BlueprintPreviewFreshTest.bug4_previewShowsTheSectionFreshSampleEmpties`, `bug4_previewDropsAWholePassageWithOneRecentMember` |
| 5 — only Blueprint is block-aware | `BlueprintBugsTest.bug5_balancedNeverSplitsAPassage`, `bug5_coverageNeverSplitsAPassage`, `bug5_weakestTopicsNeverSplitsAPassage` |
| 6 — `poolSize` without a blueprint | `BlueprintBugsTest.bug6_poolSizeWithoutABlueprintIsNotTheWholeScope` |

The matching `…Guard` tests are **not** ignored. They pin behaviour that is
already correct, so a fix cannot over-correct: a legal overshoot still runs,
preview and run agree when they share one seed, the gate still allows a
fitting block, the Fresh below-2x rule is respected, a different requested
length is not comparable, and Blueprint never splits a passage.

Un-ignoring all fourteen pins against the code of this commit fails every one,
each with the reason given in §8.

---

## 10. Quick code map

| Question | Function |
|---|---|
| How is a terminal's pool counted? | `McqRepository.blueprintSectionViews`, `TestSelector.viewOf` |
| How is the union built? | `McqRepository.buildPlanBlueprint`, `PlanBlueprint.categoryIds` |
| How are quotas split? | `BlueprintQuotas.allocate` -> `allocateLevel` |
| What is "short by"? | `BlueprintQuotas.demand` |
| How do percentages work? | `BlueprintQuotas.shares` (largest remainder) |
| How are questions taken? | `TestSelector.blueprint` |
| How are passages kept whole? | `passageKey`, `groupsByPassage`, `takeWholePassages` |
| How is a short run filled? | `fillWithWholePassages` |
| What is recorded on the attempt? | `Composition`, `CompositionSection`, `CompositionSegment` |
| What does the setup show? | `TestSelector.preview`, `SelectionPreview`, `TestSetupSheet.BlueprintPreviewRows` |
| When does the run clamp/short? | `TestPlan.resolve` (`BLUEPRINT` branch), `TestPlan.maxRequestForBlueprint`, `TestSetup.samplerRefusal` |
| Where is the run frozen? | `TestViewModel.buildPlan`, `focusSelection`, `SessionOrder` |
