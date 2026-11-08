# Phase 2 implementation plan: study-informed focus types and evidence

Status: **shipped on `feature/phase2-stats`, PR #51**. Branch cut from `main`
at `991484f` (the merge of PR #50). Slices 1–7 below are implemented in that
one PR: every practice selector including Tricky, At-risk, Unseen, Balanced
and Mixed, the setup-sheet composition preview/target display and the session
practice badge. Only the custom types (Bookmarked, Tag, Authored difficulty,
Slowest) remain for phase 3. Phase 1 is merged and released through the normal
PR flow.

This document is the working plan for the phase-2 items in
[`stats.md`](stats.md) §11.15 and §13. It records the file layout, queries,
formulas, test seams and open decisions. When a slice ships, this document and
`stats.md` are updated together; an item that is deferred stays visibly
deferred here.

## 1. Goal and exit criteria

Phase 2 answers two questions the phase-1 report cannot:

1. **Which questions and topics should I practise next, and why?** — Weakest
   topics, Toughest questions and Easiest questions selectors built on
   Study-derived signals, plus the existing Mistakes selector.
2. **Why did this attempt go the way it did, and is it improving?** — FSRS
   memory-vs-performance quadrants, coverage/repeats/mistakes/due evidence,
   per-question lifetime accuracy, a comparable trend with confidence bands,
   a bootstrap interval and practice-specific recommendations.

Exit criteria:

- The setup sheet offers Random, Balanced, Mistakes and the seven focus types
  (Weakest topics, Toughest questions, Easiest questions, Tricky, At risk,
  Unseen, Mixed); the custom `TestType`s remain unselectable.
- A focus attempt records its type, targets and composition, and resumes with
  the same ids without re-ranking.
- A practice result never claims mock comparability and never enters the mock
  trend.
- The result page shows the phase-2 evidence for a bounded attempt and omits
  it honestly when the data is absent (no card, stale hash, small sample).
- Statistics separates mock from practice and strict from Fresh sample, and
  exposes the practice-progress views.
- Pure-JVM tests cover every rule; Robolectric/SQLite tests run on CI x86_64.

## 2. What already exists

| Area | Shipped in phase 1 | File |
|---|---|---|
| Bounded plan and setup rules | `TestPlan`, `TestSetup`, length clamp, sampler pre-check | `domain/TestPlan.kt`, `domain/TestSetup.kt` |
| Types | All enum values; `RANDOM`, `MISTAKES`, `WEAKEST_TOPICS`, `TOUGHEST_QUESTIONS`, `EASIEST_QUESTIONS` reachable | `domain/TestType.kt`, `domain/TestSetup.kt` |
| Block-aware sampler | `SessionOrder.sampledBounded` | `domain/SessionOrder.kt` |
| Attempt metadata | type, requested count, scope key, version, seed, exclusion, targets, composition | `domain/Models.kt`, `data/local/Entities.kt` |
| Report model | score, margin, family, topics, error mix, timing, actions | `domain/TestReport.kt` |
| Report data | light `ReportRow` projection, comparable attempts, paper | `ui/results/ResultsViewModel.kt` |
| Repeat avoidance | default-on setting, `RECENT_3` / `NONE` | `ui/test/TestViewModel.kt`, `ui/settings/` |
| Statistics | six SRS tabs plus a result-family legend | `ui/stats/` |
| Schema | v21; `question_stats.rep*`, `attempts` metadata, partial result index | `data/local/AppDatabase.kt` |

`targetTopicIds` and `compositionJson` existed in `Attempt` from phase 1 and
are now written by the focus selectors; a legacy attempt still reads empty.

## 3. Constraints and non-goals

- **No schema migration is expected for the first slices.** `question_stats`
  has the all-attempt and representative counters, `card_state` has the FSRS
  fields, `review_log` has `wasGuess`, and the result index exists. Add a
  migration only if a query proves it needs a column or index; do not bump the
  version speculatively.
- **Read-only Study.** Test may read `card_state` and `review_log`; it never
  writes them. `savingATestLeavesTheStudyScheduleUntouched` stays green.
- **Bounded memory.** A paper can hold 250k questions. Scope signal queries
  must stream or aggregate; do not materialise every question, card and result
  row in Kotlin. A top-N heap over chunked rows is acceptable; a full sort of
  the paper is not.
- **Pure rules in domain objects.** Wilson bounds, priors, toughness, ease,
  category weakness, allocation and the fill ladder live in JVM-testable
  objects, not in SQL or Compose.
- **`minSdk = 26`** means no window functions in SQLite queries. Keep the
  correlated-subquery style already used by `getLatestStandings`.
- **Comparability is unchanged.** The key remains
  `(paperId, testType, scopeKey, requestedCount, samplingVersion,
  exclusionMode)`. A practice attempt never compares with a mock; a Fresh
  sample never compares with a strict attempt.
- **No representative bias.** FSRS difficulty and other Study signals must
  never choose a Random/Balanced sample. They may be reported next to it.
- **No new test dependencies.** Robolectric/SQLite cannot run on this ARM64
  host; CI x86_64 is authoritative for those classes.
- **Non-goals:** Balanced, Tricky, At-risk, Unseen, Bookmarked, Tag, Authored
  difficulty, Slowest, Mixed, item analysis, export and adaptive tests. They
  stay in phase 2/3 and are called out in §9.

## 4. PR slices

Slices 1–6 ship together in PR #51 on `feature/phase2-stats` (the owner asked
for the whole phase in one PR, so the slice split below is a review map rather
than a branch plan). Slices 1–7 are all done.

### Slice 1 — signals foundation (pure domain) — **Done**

- Add `domain/signals/QuestionSignals.kt` and `domain/signals/TestRanking.kt`.
- Add `app/src/test/java/com/mcqapp/QuestionSignalsTest.kt`.
- No DAO, UI or schema change. The first consumer is the test suite; the
  selector slice immediately follows, so the new code is not orphaned for long.

Deliverables: Wilson lower bound, neutral priors, validity gate, all signal
components, toughness/ease composites, category weakness and ranking helpers.

### Slice 2 — selector and queries — **Done**

- Add the minimal read projections for scope signals, card state, guess
  aggregates, due/unseen counts and category aggregates.
- Add `domain/TestSelector.kt` for Weakest topics, Toughest questions and
  Easiest questions, including allocation, the 40% cap and the fill ladder.
- Add `app/src/test/java/com/mcqapp/TestSelectorTest.kt`.
- Wire `TestViewModel` to the selector for those three types; keep `RANDOM`
  and `MISTAKES` behaviour unchanged.
- Record `targetTopicIds` and `compositionJson` for the new types.
- Robolectric tests for the new queries; CI-only.

### Slice 3 — setup sheet and labels — **Done**

Shipped: all ten startable types with a one-line help note, the per-type pool
count, the breadth control for Weakest/Mixed, the live composition preview,
the chosen-target list with observed accuracy, and the session-header practice
badge. The report/History labels already know every family and type.

The preview is display-only; the selector still records the truth on the
attempt, so a preview race cannot change what runs.

The original slice plan (kept for reference):

- Replace the "more types will be added" note in `TestSetupSheet` with type
  cards that show the pool size and a one-line "why these questions" note.
- Show the preview composition and the practice badge in the session header,
  result header and History row.
- Add string resources; `StringResourceUsageTest` requires a literal
  `stringResource(R.string.x)` call, so keep resource lookups in `Text(...)`,
  lambdas or `?:` branches.
- Compose/UI tests where the project already has them; otherwise the pure
  preview values are tested in `TestSetupTest`.

### Slice 4 — report evidence — **Done**

- Extend `TestReportInput` and `TestReport` with `memory`, `coverage`,
  `mistakes` and per-question lifetime accuracy.
- Add the query seams in `McqRepository` and `ResultsViewModel`.
- Render the quadrants, coverage/repeats/mistakes/due and Study badges in
  `ResultsScreen`.
- Extend `TestReportBuilderTest` with valid, stale and missing `card_state`.

### Slice 5 — trend and bootstrap — **Done**

- Add the comparable trend model and confidence bands, including the
  fresh-vs-fresh approximation note.
- Add the bootstrap interval for negative marking, resampling passage blocks
  with a deterministic seed.
- Add the practice headline and practice-specific recommendations.
- Render the trend in `ResultsScreen` and the History row where appropriate.
- Extend `TestReportBuilderTest` and `RecommendationEngineTest`.

### Slice 6 — Statistics families and practice progress — **Done**

- Split attempt aggregates by family (Mock / Fresh / Practice / Short /
  Legacy) and by strict vs fresh.
- Add the practice-progress views: weak-topic accuracy, mistakes cleared,
  leeches, at-risk, unseen coverage.
- The existing Overview legend stays; the shipped home is a seventh
  **Tests** tab (`ui/stats/sections/TestsSection.kt`) rather than extra
  Overview charts, because the family split is a different question from the
  SRS overview.

### Slice 7 — remaining selectors — **Done**

Tricky, At-risk, Unseen, Balanced and Mixed are implemented in
`TestSelector`, startable from the sheet and labelled in the session, report
and History. Custom types (Bookmarked, Tag, Authored difficulty, Slowest)
stay deferred to phase 3: exact tag filtering needs a `question_tags` table.

## 5. Domain design

### 5.1 `QuestionSignals`

One immutable value per question, built from counters and an optional card:

```kotlin
data class QuestionSignalInput(
    val questionId: String,
    val categoryId: String?,
    val authoredDifficulty: AuthoredDifficulty?, // EASY / MEDIUM / HARD / null
    val correct: Int,
    val wrong: Int,
    val skip: Int,
    val graded: Int,
    val repCorrect: Int,
    val repWrong: Int,
    val repSkip: Int,
    val guesses: Int,
    val reviewsWithGuessFlag: Int,
    val card: CardSignal? // null when there is no card_state row
)

data class CardSignal(
    val difficulty: Double,      // FSRS 1..10
    val stability: Double,
    val reviewCount: Int,
    val lapses: Int,
    val leech: Boolean,
    val dueAt: Long,
    val lastReviewedAt: Long,
    val contentHashMatches: Boolean
)
```

`QuestionSignals.of(input, counterSet, now, scheduler)` returns:

```kotlin
data class QuestionSignals(
    val questionId: String,
    val categoryId: String?,
    val weakness: Double,        // 0..1, higher = weaker
    val skip: Double,            // 0..1
    val guess: Double,           // 0..1
    val lapse: Double,           // 0..1
    val difficulty: Double,      // 0..1 blended prior
    val memoryRisk: Double,      // 0..1
    val unseen: Boolean,
    val stale: Boolean,
    val fsrsAvailable: Boolean,
    val toughness: Double,
    val ease: Double
)
```

### 5.2 Exact rules

Use the formulas in `stats.md` §9.2 without re-deriving them:

- `wilsonLower(p, n, z = 1.96)` with the published formula; clamp `n <= 0` to
  a neutral result and keep the result in `0..1`.
- `weakness = 1 - wilsonLower(correct / answered, answered)` when
  `answered >= 3`, else `0.5`. The representative ranking reads
  `repCorrect / repAnswered`; the personal ranking reads
  `correct / (correct + wrong)`.
- `skip = skipCount / gradedCount` when `graded >= 3`, else `0.5`.
- `guess = guesses / reviewsWithGuessFlag` when the flag count is `>= 3`,
  else `0`.
- `lapse = min(1, lapses / max(reviewCount, 1) * 2)`; `1` when leech.
- `fsrsAvailable` only when `stability > 0`, `reviewCount > 0`, the card is not
  `NEW` and `contentHashMatches`. A stale card is treated as new.
- `fsrsDifficulty = (difficulty - 1) / 9`; `authoredDifficulty` maps
  `EASY = 0`, `MEDIUM = 0.5`, `HARD = 1`.
- `difficultyEvidence = min(1, reviewCount / 5)` and
  `difficulty = evidence * fsrsDifficulty + (1 - evidence) * authored`.
  With no FSRS evidence, use the authored value; with neither, `0.5`.
- `memoryRisk = 1 - scheduler.retention(card, now)` when FSRS is valid, else
  `0.5`.
- `unseen` when there is no card, the card is new, or the hash is stale.
- `toughness = 0.35 * weakness + 0.20 * difficulty + 0.20 * memoryRisk +
  0.15 * lapse + 0.10 * skip`.
- `ease = 0.45 * (1 - weakness) + 0.25 * (1 - difficulty) +
  0.20 * (1 - memoryRisk) + 0.10 * (1 - lapse)`.

Counter-set choice is explicit, never implicit:

| Consumer | Counter set |
|---|---|
| Random/Balanced weakness, mock report lifetime accuracy | representative `rep*` |
| Weakest topics, Toughest, Easiest, Mistakes, Tricky | all-attempt |
| Practice report lifetime accuracy | all-attempt |

`QuestionSignalsTest` must pin the boundary cases: `answered = 2` vs `3`,
zero graded, a stale hash, a new card, a leech, a one-review FSRS estimate and
the neutral priors.

### 5.3 `TestRanking`

Pure helpers over a list of signals:

- `rankToughest(signals, n, categoryCap)` — descending toughness, stable
  tie-break by question id.
- `rankEasiest(signals, n, excludeLeechesAndAtRisk)` — descending ease,
  excluding leeches and at-risk cards when enough alternatives remain.
- `weakestCategories(categoryAggregates, scopeSize, n, breadth)` — category
  weakness from the category formula, then `K` by breadth:
  narrow `1`, medium `3` (default), broad `5`.
- `allocate(count, categories)` — largest remainder with caps, minimum one per
  selected category when `n >= categories`, and the `max(1, ceil(0.40 * n))`
  cap that relaxes to `ceil(n / K)` when infeasible.

### 5.4 `TestSelector`

The selector turns a plan plus a scope into ids and a composition. It does not
touch the database; the repository supplies candidate rows and the selector
returns a `Selection` value:

```kotlin
data class Selection(
    val ids: List<String>,
    val targets: List<String>,          // sorted, deduped category ids
    val composition: Composition
)
```

Rules to pin in `TestSelectorTest`:

- Weakest topics expands the ticked scope once with
  `CategorySelection.orderedIds`; parents keep their own questions.
- Top-K categories are capped by supply; if they cannot fill `n`, add the
  next-weakest categories.
- Allocation is proportional by category size with the largest-remainder
  method, at least one per selected category when possible.
- The 40% cap applies to Toughest (and later Tricky); two categories at `n =
  20` become `10 + 10`.
- Toughest takes the top `n` after the cap. Decide whether to sample from the
  top `2n` for variety (§10, decision 3).
- Easiest samples from the top `2n` by ease, excluding leeches and at-risk
  cards when alternatives exist.
- The fill ladder is explicit and recorded in the composition:

| Type | Fill order |
|---|---|
| Weakest topics | next-weakest categories, then random |
| Toughest | authored `HARD` without enough evidence, then random |
| Easiest | authored `MEDIUM`, then random |
| Mistakes | Tricky, then Toughest, then Random |

- A focus test that needed a large random fill says so in the preview and the
  result (`12 weakest + 8 random`), never silently looks like a pure type.

## 6. Data and queries

No migration is planned. Proposed query seams (names are indicative):

| Query | Owner | Shape | Notes |
|---|---|---|---|
| Scope signal rows | `QuestionStatsDao` | question id, category id, all counters, `rep*` | Chunk the `IN` list; project only counters. |
| Scope card states | `CardStateDao` | paper/category rows | Needed for difficulty, stability, lapses, leech, due, hash. |
| Question content hashes and authored difficulty | `QuestionDao` | id, hash, difficulty | Join in SQL or match in Kotlin. |
| Guess aggregates | `ReviewLogDao` | question id, guesses, flagged reviews | Ignore null `wasGuess` (pre-v19). |
| Category aggregates | `QuestionStatsDao` | category, counts | Sum in SQL, blend in Kotlin. |
| Due count for ids | `CardStateDao` | count | `dueAt <= now`, non-new, valid hash. |
| Unseen count for scope | `QuestionDao` / `CardStateDao` | count | No row, new, or stale hash. |
| Prior mistake standings | `AttemptDao` | question id, correct, finishedAt, row id | Before this attempt's `finishedAt`; same `finishedAt DESC, id DESC` rule. |
| Coverage | `AttemptDao` | engaged count | `selectedOptionIds != '' OR dwellSeconds > 0`. |
| Repeats | `AttemptDao` | question ids seen before | Count this attempt's ids that appear in earlier results. |
| Lifetime accuracy for report ids | `QuestionStatsDao` | counters by id | `rep*` for mock, all-attempt for practice. |

Notes:

- The existing `getLatestStandings` and mistake-order queries already encode
  the standing rule; reuse them rather than adding a second definition.
- For top-N selectors, stream scope rows in chunks and keep a bounded heap in
  Kotlin. Do not sort the whole paper in memory.
- The report already loads at most 150 rows; joining card state and lifetime
  counters for those ids is bounded and safe.
- If a query needs an index, add it in a dedicated migration and update the
  schema test; do not add it speculatively.

## 7. Recording and resume

- `targetTopicIds` is the sorted, deduped set of categories the Weakest-topics
  selector chose, even when the final sample misses one because of passage
  blocks. `TestPlan.validationError` already requires canonical order.
- `compositionJson` describes the fill segments, for example:

```json
{"version":1,"segments":[
  {"source":"WEAKEST_TOPICS","count":12},
  {"source":"RANDOM","count":8}
]}
```

  Parse it with a small pure codec; an unknown or malformed value renders no
  composition rather than crashing.
- Resume uses `selectedQuestionIds` exactly as phase 1 does. A focus snapshot
  never re-ranks after metrics change; that is what makes the attempt
  reproducible.
- A legacy snapshot keeps its legacy metadata. A bounded focus snapshot whose
  targets no longer resolve still resumes with its recorded ids and is
  labelled with its original type.

## 8. UI plan

### Setup sheet

Shipped: all ten startable type chips with a one-line help note, the existing
scope/length/time controls, the per-type pool count, a breadth control for
Weakest/Mixed, the live composition preview and the chosen-target list with
observed accuracy. Changing type, length or breadth re-reads the preview; the
selector still records the real composition on the attempt.

- The sheet must not offer a type whose pool is empty; the existing Start
  validation and sampler pre-check remain the final gate.
- The Fresh sample note appears only for types with `supportsFreshSample`;
  practice types show the practice note instead.

### Results — **shipped**

- Memory card: the four quadrants (hard/wrong, hard/correct, easy/wrong,
  easy/correct), "no Study history", due-in-Study and retrievability. Every
  value is labelled as a Study estimate.
- Coverage card: engaged / scope size, unseen remaining, repeat rate, mistakes
  added/cleared/remaining and due in Study.
- Question review: lifetime accuracy with its source label
  (`Mock attempts` vs `All attempts`).
- Practice headline: the targets and a reminder that the score is biased by
  design; no mock trend or margin.
- Trend: only for comparable families; fresh-vs-fresh gets the approximate
  note; short and legacy get none.
- Bootstrap interval: only when negative marking makes the accuracy margin an
  incomplete description of the score.

### Statistics — **shipped**

- A seventh **Tests** tab separates Mock, Fresh sample, Practice, Short and
  Legacy in the attempt aggregates.
- Practice progress: the last three focus runs with target accuracy and
  mistakes cleared, plus leeches, at-risk and unseen coverage for the scope.
- No practice score enters a mock trend.

## 9. Tests

### Pure JVM (local, authoritative for rules)

- `QuestionSignalsTest`: Wilson values and boundaries; neutral priors;
  stale/new/leech cards; difficulty blend at 1, 4 and 5 reviews; toughness and
  ease ordering; representative vs personal counter sets; category weakness.
- `TestSelectorTest`: scope expansion, parent dedupe, largest remainder,
  minimum one, 40% cap and relaxation, fill ladder, Mistakes standing rule,
  Weakest targets recorded even when a target is missed, Easiest exclusion.
- `TestSetupTest`: the new types resolve, clamp and refuse exactly like the
  existing ones; the startable set is exactly the ten shipped types.
- `TestReportBuilderTest`: memory quadrants with valid, stale and missing
  card state; coverage counts engaged rows only; mistakes added/cleared;
  due-in-Study; lifetime accuracy source; trend comparability; bootstrap
  determinism and passage resampling; practice headline.
- `TestProgressTest` / `TrendsTest`: families stay separate, practice runs
  are newest-first, a Fresh sample keeps its own line and a legacy attempt
  gets none.
- `RecommendationEngineTest` / `TestReportBuilderTest`: each new rule fires
  only on its evidence; no practice action claims mock comparability; the
  practice trend needs two same-target runs and never feeds the mock line;
  the review tags use the error-mix thresholds; short/legacy suppress
  statistical actions.
- `TestSnapshotTest` / `TestPlanTest`: focus metadata round-trips and a
  resumed focus session keeps its ids.

### Robolectric / SQLite (CI x86_64 only)

- New DAO queries return the expected counts and order, including chunked
  scopes and empty scopes. **Done (core reads):** `SignalQueryTest` pins the
  due/unseen signal read and the same-target practice history.
- `getLatestStandings`-style prior standings match `finishedAt DESC, id DESC`.
  **Deferred** for the new chunked query; the pure standing rule is tested.
- A focus attempt writes `targetTopicIds` and `compositionJson`; a resumed
  attempt does not re-rank. The round-trip is covered by
  `TestPlanTest`/`TestSnapshotTest`; `SignalQueryTest` also seeds two focus
  attempts and asserts the recorded targets drive the practice history.
- Deleting an attempt recomputes the affected `question_stats` rows; a
  practice attempt never moves `rep*`.
- Report queries match the header: score, counts, categories, dwell median,
  engaged coverage, due count and lifetime accuracy.
- Saving and reading the report leaves `card_state` and `review_log`
  untouched.

### UI / ViewModel

- Start each focus type from the sheet and land on the expected pool.
- A type with an empty pool is disabled; a sampler pre-check refusal still
  shows the specific message.
- A practice result shows the practice headline and no trend.
- A mock result shows the quadrants, coverage and lifetime accuracy.
- Every action route still navigates correctly.
- The setup sheet never emits a type the session refuses.

## 10. Open decisions

1. **First code slice.** Recommendation: ship `QuestionSignals` plus
   `TestSelector` in one PR (slices 1–2) so the foundation has a real
   consumer; keep the UI cards in the next PR.
2. **Balanced.** Recommendation: keep it out of phase 2's first release. The
   spec lists it as following the focus types, and it needs a separate
   stratified-allocation contract.
3. **Toughest variety.** Recommendation: strict top-N with the category cap
   for the first release, then add seeded top-2N sampling only if repeated
   tests feel identical. Determinism is easier to test and explain.
4. **Easiest exclusions.** Recommendation: exclude leeches and at-risk cards
   only when at least `n` alternatives remain; otherwise keep them and say so
   in the composition note.
5. **Fresh sample on Easiest.** `TestType.supportsFreshSample` already includes
   it. Recommendation: enable it only after the selector lands, and keep the
   practice label (a Fresh sample is not automatically comparable).
6. **Bootstrap size.** Recommendation: 1,000 resamples of passage blocks,
   seeded from the attempt seed, with a fixed 95% interval. State the cost and
   keep it off the main thread.
7. **Trend minimum.** Recommendation: keep the phase-1 `trendWorthy` rule
   (shown count or margin) and add a minimum of two comparable points; one
   point is a dot, not a trend.
8. **Statistics tabs vs Overview.** Recommendation: add the Exam/Coverage/
   Progress tabs from `statistics-plan.md` only when their data exists;
   otherwise extend the Overview cards and keep the legend.
9. **Composition schema.** Recommendation: versioned JSON with a tiny pure
   codec, unknown fields ignored. Do not put prose in the database.
10. **Tricky/At-risk/Unseen timing.** Recommendation: defer to a separate
    phase-2 follow-up after the first five types are stable; they need
    different evidence and labels. **Landed** with Balanced and Mixed in slice
    7.

## 11. Risks and mitigations

- **Stale `contentHash`** can silently turn a studied card into a new one.
  Mitigation: the validity gate is one function, tested once, and every
  consumer uses it.
- **Legacy skip inflation** weakens skip-based signals. Mitigation: label the
  evidence and never let skip alone drive a representative ranking.
- **Small samples** produce confident-looking rankings. Mitigation: Wilson
  lower bound, neutral priors and explicit small-sample flags.
- **Large scopes** can blow memory. Mitigation: chunked projections and a
  bounded top-N heap; no full-paper sort.
- **Practice contamination** of mock numbers. Mitigation: the counter-set
  choice is explicit and tested; `rep*` stays representative-only.
- **Resume drift** after metrics change. Mitigation: resume replays recorded
  ids and never re-ranks.
- **ARM64 test gap.** Mitigation: pure-JVM tests locally; Robolectric and
  instrumented tests on CI x86_64 before merge.

## 12. Shipped order

1. Slice 1 + 2: signals, selector, queries, tests; no UI. **Done.**
2. Slice 3: setup type chips, pool count, composition preview and session
   practice badge. **Done.**
3. Slice 4: report evidence. **Done.**
4. Slice 5: trend, bootstrap and practice headline. **Done.**
5. Slice 6: Statistics families and practice progress. **Done.**
6. Slice 7: Tricky, At-risk, Unseen, Balanced, Mixed and the remaining
   report/Statistics views. **Done.**

`stats.md` §11.15/§13 and the README are updated with this PR. A deferred item
stays marked deferred until its code and tests land.
