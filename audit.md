# Audit: FSRS-6 pull-based scheduling

- **Scope:** everything after release `0.0.18` (tagged at `928ecde`), i.e. the 16 commits
  on `exp/fsrs-scheduling` in PR #2, plus the local-only `75e598a`.
- **Date:** 2026-10-13
- **Diffstat:** 38 files, +2572 / −2409 (was 35 files / +2584 / −1523 before `75e598a`
  deleted five planning docs).
- **CI:** green on the pushed tip `a333a9f` (build, ui-test, release-build,
  wrapper-validation). `75e598a` has never been built by CI.

## Method

Static read of the whole diff plus full-file review of the changed layers
(domain, data, repository, study UI, interop, tests), a cross-check of the ported
FSRS-6 formulas against the upstream reference implementation, and a numerical
replay of the app's own claimed reference vectors.

**FSRS-6 verification.** Upstream `open-spaced-repetition/py-fsrs` `fsrs/scheduler.py`
was ported to Python (scratch, not committed) and validated by reproducing py-fsrs's
own test vectors, then run side by side with a faithful replica of `FsrsScheduler`:

| Check | Result |
|---|---|
| py-fsrs port vs its own `test_review_card` | `[0,2,11,46,163,498,0,0,2,4,7,12,21]` — exact |
| py-fsrs port vs its own `test_memo_state` | `53.626903 / 6.357487` vs expected `53.62691 / 6.3574867` — exact |
| `FsrsScheduler` vs py-fsrs on the same history | identical to 6 dp (stability and difficulty) |
| py-fsrs with `learning_steps=()` vs `FsrsScheduler` | divergent only where the ladder was deliberately dropped |

## Verdict

The FSRS-6 engine, the v10 migration, the transactional review write and the pull-based
ranking are sound and well tested. Two medium-severity issues need fixing before merge:
`review_log` rows are never cleaned up when a question is deleted, and every card pull
re-derives the whole paper. A third medium issue is a latent wrong-branch in the engine
for cards imported from Anki without a review log.

---

## Findings

### 1. Medium — `review_log` rows are orphaned when a question is deleted

`app/src/main/java/com/mcqapp/data/repository/QuestionStore.kt:238`, `:251`, `:294`
(`cardStateDao().deleteByQuestion` / `deleteByQuestionsChunked`)

Deleting a question, bulk-deleting questions, or moving a question across papers all
remove its `card_state` row. `review_log` has no equivalent: `ReviewLogDao` exposes only
`insert`, `historyForQuestion` and `countByPaper` (`Daos.kt:413-427`), and its only
foreign key is `paperId → papers`, so no cascade covers a question deletion.

Consequences:

- Rows accumulate unboundedly for question ids that no longer exist.
- Question ids are generated with `-copy`-style suffixes (`QuestionStore.kt:258-269`);
  if an id is ever reused, its history is polluted with reviews of the deleted card.
- The README states the opposite. `README.md:136-138`: "deleting a paper, or a
  question, removes its schedule **and its logged reviews**".
- `review_log` is also not exported or restored by JSON backup / Anki import, so a
  "full backup" silently discards the immutable history the README calls the roadmap's
  optimizer input (`README.md:139-141`). That this is local-only is not documented.

Fix: add a `DELETE FROM review_log WHERE questionId = (:ids)` DAO and call it in the
same `withTransaction` as the three `card_state` deletes, and state the backup
exclusion in the README.

### 2. Medium — every card pull re-reads and re-hashes the entire paper

`app/src/main/java/com/mcqapp/data/repository/StudyStore.kt:125-147`

`getNextStudyCard` (the path `StudyViewModel.pullCard` runs after **every** grade)
calls `questions.getQuestionsForPaper(paperId)`, which loads every question with its
options and answer keys, then computes `mapper.contentHashOf(it)` per question, which
parses the stem and every option's `elements` JSON.

This is exactly the work that was optimised away for badges in this same release:
`StudyStore.studyInputs()` (`:186-206`) reads ids and content hashes from the questions
and options rows only. The pre-release design built the queue once per session; the
pull design rebuilds it per graded card, so a 30-card sitting on a 500-question paper
does 30 full-paper loads plus ~15k JSON parses.

Fix: have `getNextStudyCard`/`getStudyQueue` use `studyInputs()` like `getStudyCounts`
does. The pull only needs question ids, content hashes and card states — it never
touches option text or the answer key.

### 3. Medium — an Anki-imported review card silently takes the same-day formula

`app/src/main/java/com/mcqapp/domain/Fsrs.kt:73-77` and
`app/src/main/java/com/mcqapp/data/anki/AnkiScheduling.kt:108-112`

```kotlin
val elapsedDays = if (state.lastReviewedAt == 0L) 0.0
                  else (now - state.lastReviewedAt).toDouble() / DAY_MS
```

`AnkiScheduling.fromAnki` sets `stability = intervalDays.toDouble()` and infers
`lastReviewedAt = max(0L, dueAt - intervalDays * DAY_MS).coerceAtMost(nowMillis)`. When
a card is due today with a long interval and the package carries no review log
(`dueAt - ivl·DAY_MS ≤ 0`), the inferred value is `0` — so the card arrives with
`stability > 0` **and** `lastReviewedAt == 0`.

The first post-import review then takes the `elapsedDays < 1.0` branch
(`shortTermStability`, growth floored at 1.0) instead of the recall formula.
Numerically confirmed: a card at `S = 30`, `D = 5` graded Good one day later gives
`S' = 30.000` where the reference gives `32.62`.

`retention()` (`Fsrs.kt:152-156`) also returns `0.0` for that card, so it is ranked
`LOW_RECALL_RISK` and sorts ahead of every genuinely-remembered card for as long as
the bad state persists.

Fix: distinguish "never reviewed" from "reviewed at the epoch". Key the same-day
shortcut off `hadMemory` (already computed) plus an explicit "no last-review"
sentinel, rather than treating `lastReviewedAt == 0` as elapsed zero.

### 4. Low-Medium — dead and stale domain contract: `isLearning`, `isNew`, `reps`

`app/src/main/java/com/mcqapp/domain/SpacedRepetition.kt:62-65`,
`app/src/main/java/com/mcqapp/domain/Fsrs.kt:136`, `:143`

- `CardState.isLearning` (`reps == 0 && dueAt != 0L`) is unreachable: `next()` sets
  `reps = state.reviewCount + 1` on every grade, so an Again on a new card leaves
  `reps == 1`, not 0. It has **zero** readers anywhere in the codebase.
- The `isNew` KDoc still documents the old SM-2 rule: "An 'Again' answer also leaves
  [reps] at zero…", which the FSRS engine no longer does.
- `reps` and `reviewCount` are written as two independent expressions
  (`reps = state.reviewCount + 1`, `reviewCount = state.reviewCount + 1`) that are
  always equal by construction. No test asserts the mirror invariant, so a future
  edit can desync them silently.

Fix: delete `isLearning` (or give it a real meaning, e.g. `state == LEARNING && dueAt > 0`),
correct the `isNew` doc, and add a test that `next()` keeps `reps == reviewCount`.

### 5. Low — the reference-ladder test overstates parity

`app/src/test/java/com/mcqapp/FsrsSchedulerTest.kt:69-98`

The comment claims that replaying the reference's history "reproduces the reference's
difficulty lineage, so every later interval must match it exactly". With
`learning_steps` removed — the app's deliberate deviation — the reference itself yields
`[2, 11, 46, 163, 497, 1346, …]`, not `[2, 2, 11, 46, 163, 498]`. The app's asserted
values are correct **for the app's implementation** (verified by replay), but they are
not the reference's ladder: the extra leading `2` is the app's immediate graduation and
the `498` vs `497` shows the histories are not the same.

Only `canonicalBenchmarkSequenceMatchesTheReferenceValues` (`:43-66`) is a genuine
vector test, and it passes exactly. Reword the ladder test's comment so it does not
claim reference parity it does not have.

### 6. Low — comment says "rounded", code truncates

`app/src/main/java/com/mcqapp/data/anki/AnkiScheduling.kt:187` vs the KDoc at `:150-156`

```kotlin
val daysFromExport = ((state.dueAt - startOfDay(crtSeconds * 1000L)).toDouble() / DAY_MS)
    .toLong().coerceAtLeast(0L).toInt()
```

`toLong()` truncates toward zero; the doc says "Days are rounded, not truncated,
because Anki's own day boundary rolls over at 04:00". Pre-existing (not in this
release's diff), but the release touched this function. Either round or fix the
comment.

### 7. Low — unpushed commit, dangling doc references, vanished scratch work

- `75e598a deleted leagcy files` (audit.md, ideas.md, PASSAGE-QUESTIONS.md,
  SCHEDULING-PARITY.md, SIMPLIFIED-STUDY.md) is local-only; CI never built it. Docs
  only, so risk is low, but the AGENTS.md rule is to verify before relying on it.
- Four code comments still cite deleted files:
  `PassageStore.kt:18` and `PassageBlocks.kt:6`/`PassageBlocksTest.kt:16` cite
  `PASSAGE-QUESTIONS.md`; `FSRS-PLAN.md:14` and `:322` cite `SCHEDULING-PARITY.md`.
- `code_review.md`, `test_document/` and `app/src/test/java/com/mcqapp/ScratchBrowseButtonsTest.kt`
  were present in the working tree at the start of this audit and were removed by a
  parallel commit mid-audit. If the scratch test was meant to survive, it now exists
  only as stale class files under `app/build/`.

### 8. Low — new settings copy bypasses `strings.xml`

`app/src/main/java/com/mcqapp/ui/settings/SettingsScreen.kt:550-611`

"Target retention", "Ranking mode", "Memory first", "Explore new cards", "Introduce
new cards" and their help texts are literal English strings, while every other screen
goes through `stringResource`. `strings.xml` kept a string still named
`sm_2_scheduling_for_study_these_match_anki_s_review_options_` whose value now reads
"FSRS scheduling for Study". `StringResourceUsageTest` only checks that
`stringResource` calls are rendered, so it does not catch either.

### 9. Nit — `ReviewLogDao` has two unused reads

`app/src/main/java/com/mcqapp/data/local/Daos.kt:419-427`

`historyForQuestion` and `countByPaper` have no main-source callers; only
`MigrationTest` calls them. `NoDeadCodeTest` enforces dead-code removal elsewhere in
the project. Use them (e.g. show a card's review history) or drop them until the
optimizer lands.

---

## Coverage notes

Good and worth keeping:

- `FsrsSchedulerTest` covers initial states for all four ratings, retrievability at
  `t = 0 / S / 2S`, same-day Hard flooring, lapse→relearn, the stability floor under
  1000 failures, leech at 8 lapses, the day-boundary snap, the retention ceiling,
  preview-equals-persist, and the 21-parameter guard.
- `SpacedRepetitionTest` was fully rewritten around pools: priority order, risk
  ranking with the oldest-due tie-break, leech handling, explore pacing, toggle-off,
  and two invariants (badge total == pool size, every question either eligible or not).
- `MigrationTest` builds a genuine v9 file by replaying the production `Migration`
  objects, so the fixture cannot drift, and asserts the wipe plus the new columns and
  `review_log`.
- Interop tests cover legacy-vs-FSRS schedule import and the Anki kind/stability/
  difficulty derivation.

Gaps worth adding:

1. An imported Anki card with `stability > 0` and `lastReviewedAt == 0` (finding 3) —
  a regression test for which branch `next()` takes.
2. `reps == reviewCount` after every grade (finding 4).
3. Question deletion removes `review_log` rows (finding 1).
4. A large-paper session asserts a bounded number of queries (finding 2) — the project
   already has `CopyQueryCountTest` and `ImportCollisionQueryCountTest` to copy.
