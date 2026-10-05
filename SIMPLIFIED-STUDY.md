# Simplified Study — Tracked Work Plan

Goal: a study mode where the learner just answers questions and the app grades.
No Again/Hard/Good/Easy decision per card. On by default.

- Baseline: `master` past `0.0.9`, suite `OK (714 tests)`
- Rules: **one commit per track**; CI must stay green
- Verify JVM with `sh /tmp/run-qemu-tests.sh`; `androidTest` only via CI `ui-test`

---

## Agreed design (2026-10-05, do not re-litigate without cause)

**One engine, two inputs.** SM-2, `card_state`, `recordStudyReview`, queue, badge,
export — all untouched. The toggle changes only where a grade comes from: finger
(manual) vs inference (simplified). Flipping it mid-stream is safe; schedules are
grade-agnostic snapshots.

**Grade mapping (simplified):**

| | Correct | Wrong |
|---|---|---|
| Not guessing | dwell-based via existing `inferGrade`: fast → EASY, medium → GOOD, slow → HARD | AGAIN |
| Guessing | **HARD**, dwell ignored | AGAIN |

Correct means **exact match** (`selection == correctOptionIds`); a partially right
multi-correct answer is wrong. Skipped (nothing selected) is AGAIN.

**The guess flag is pre-commit.** Toggled before reveal, locked after, reset every
question, never persisted. Its entire effect is delivered at grade time into
`card_state` (shorter interval + lower ease via HARD); there is nothing left to
store, which is why no schema change is needed. For a review card at 10d the
difference is ~25d (GOOD) vs ~12d (HARD); for a new card both read "1d" but the
ease haircut compounds — so the result line always shows the grade, not just the
interval.

**Check proposes, Next persists.** `check()` computes the proposed grade and shows
it; `next()` persists via `recordStudyReview` and advances. The change-grade
affordance edits the proposal, so no double-persist and no new repository API.
Leaving without Next loses the review — identical to leaving manual mode without
grading.

**Manual mode is byte-for-byte unchanged**, previews included. Users who leave the
toggle off see nothing new.

---

## Decisions (answered 2026-10-05)

| # | Question | Decision |
|---|---|---|
| G1 | Toggle name and default | "Simplified Anki", DataStore key `simplified_study`, default `true` — including for existing installs (it only removes a decision) |
| G2 | Where does the setting live | UI/behaviour pref in `McqRepository` (same pattern as `shuffle_questions`), **not** in `SchedulerConfig` — it changes grading input, not scheduling parameters |
| G3 | Correctness rule for multi-correct | Exact match; partial is wrong |
| G4 | Correct-guess grade | HARD (keeps reps, small growth, ease haircut) — not AGAIN (which would reset reps and feed leech counting) |
| G5 | Check vs persist split | Check proposes, Next persists; change-grade edits the proposal |
| G6 | Guess flag storage | Ephemeral in `StudyUiState`, reset on advance/restart; locked after reveal |

---

## Track S1 — Setting

**Status:** ☑ done

### S1.1 — Repository pref
- [ ] `simplifiedStudyKey = booleanPreferencesKey("simplified_study")` beside
      `shuffleQuestionsKey` (`McqRepository.kt:78`)
- [ ] `fun simplifiedStudy(): Flow<Boolean>` defaulting to `true` (`:81` pattern)
- [ ] `suspend fun setSimplifiedStudy(enabled: Boolean)` (`:84` pattern)

### S1.2 — ViewModel wiring
- [ ] `_simplifiedStudy` / `simplifiedStudy` StateFlow beside `_shuffleQuestions`
      (`SettingsViewModel.kt:35-36`), collected in `init` (`:43` pattern)
- [ ] `fun setSimplifiedStudy(enabled: Boolean)` launching the repository setter

### S1.3 — Settings UI
- [ ] Switch row "Simplified Anki" with help "Grade study cards automatically from
      your answers instead of picking Again/Hard/Good/Easy." Placed at the top of
      the Study section, above the scheduler knobs, so the default experience is
      explained before the tuning underneath it

**Commit:** `feat(study): add Simplified Anki setting (default on)`

---

## Track S2 — Automatic grading logic + ViewModel

**Status:** ☑ done (`7869bcd`)

### S2.1 — Pure grade function (testable without Android)
- [ ] `Study.autoGrade(correctOptionIds, selection, dwellSeconds, isGuess, config)`
      in `SpacedRepetition.kt`, beside `inferGrade` (`:301`):
      empty selection → AGAIN; `selection != correctOptionIds` → AGAIN;
      correct + guess → HARD; correct, no guess → `inferGrade(true, dwell, config)`
- [ ] KDoc stating the exact-match rule and why guess bypasses dwell (deliberation
      time on a random pick is meaningless)

### S2.2 — ViewModel state
- [ ] `StudyUiState` gains: `simplified: Boolean`, `isGuess: Boolean`,
      `questionStartedAt: Long`, `pendingGrade: ReviewGrade?`,
      `lastResult: StudyResult?` where
      `StudyResult(correct: Boolean, grade: ReviewGrade, reason: String, nextIn: String)`
- [ ] Load reads `repository.simplifiedStudy()` once per session alongside the
      queue (same place `config` is read today)
- [ ] `questionStartedAt` set on load, advance, and restart

### S2.3 — ViewModel behaviour
- [ ] `fun setGuess(guessing: Boolean)` — ignored when `revealed` (lock) or when
      not simplified; logs the mark
- [ ] `fun check()` — no-op when already revealed or grading; computes dwell from
      `questionStartedAt`, correctness by exact match, proposed grade via
      `autoGrade`; sets `revealed = true`, `pendingGrade`, `lastResult` (with
      `nextIn` read off `previewDelays` for the proposed grade — reuse, don't
      recompute); does **not** persist
- [ ] `fun changeGrade(grade)` — replaces `pendingGrade`, recomputes `lastResult`;
      no-op unless a proposal exists
- [ ] `fun next()` — persists `pendingGrade` via `recordStudyReview`, advances
      `index`, clears selection/guess/result/pending, resets `questionStartedAt`,
      refreshes previews (harmless in simplified; keeps one code path); guards
      double-tap with the existing `grading` flag
- [ ] `restart()` clears the simplified fields alongside the existing reset
- [ ] Manual path (`grade()`) untouched

**Commit:** `feat(study): automatic grading with pre-answer guess flag`

---

## Track S3 — Simplified screen

**Status:** ☑ done (`a93bc34`)

### S3.1 — Branch
- [ ] Where `StudyScreen.kt:143-155` shows Show-answer-then-`GradeButtons`, branch
      on `state.simplified`: simplified renders the new flow, otherwise the
      existing block verbatim (manual mode must not shift by a pixel)

### S3.2 — Guess toggle
- [ ] Small toggle row above the options, visible only when simplified **and**
      not revealed: e.g. "Guessing?" with the dice (`Casino`) icon, wired to
      `setGuess`. Hidden after reveal — retroactive marking would defeat the
      pre-commit.
- [ ] Toggling must not disturb the current option selection

### S3.3 — Check + result + Next
- [ ] "Check" replaces "Show answer" in simplified mode (same placement, calls
      `check()`); disabled with no selection? No — an empty selection is a
      legal skip (grades AGAIN), so Check stays enabled and the result says so
- [ ] Result card after check: "✓ Correct — Hard (guessed), next in 1d" /
      "✗ Incorrect — back in 10m", reusing `IntervalFormat`; small
      "Change grade" affordance opening the four manual buttons bound to
      `changeGrade`
- [ ] "Next" button persists and advances; on the last card it finishes the
      session exactly as grading does today

**Commit:** `feat(study): simplified study screen (guess toggle, result card)`

---

## Track S4 — Tests + README

**Status:** ☑ done (this commit)

### S4.1 — Pure mapping (`SpacedRepetitionTest.kt`, runs under QEMU)
- [ ] Correct + fast → EASY, medium → GOOD, slow → HARD (boundary values at
      `fastSeconds`/`slowSeconds`)
- [ ] Correct + guess → HARD at every dwell, including instant and very slow
- [ ] Wrong + guess and wrong, no guess → AGAIN
- [ ] Empty selection → AGAIN (skip), with and without guess flag
- [ ] Multi-correct exact match → graded by dwell; any subset or superset → AGAIN
- [ ] Single-answer wrong option → AGAIN

### S4.2 — Behavioural (repository/VM level as the harness allows)
- [ ] Check does not write `card_state`; Next does, with the proposed grade
- [ ] `setGuess` after reveal is ignored; flag resets on advance and restart
- [ ] `changeGrade` replaces the pending grade; the persisted schedule matches it
- [ ] Manual `grade()` path unchanged (existing suite is the net; add nothing that
      duplicates it)

### S4.3 — Docs
- [ ] README study-mode section: the two modes, the mapping table, the guess
      rule (pre-answer, locked after reveal), exact-match for multi-correct,
      Check-proposes/Next-persists

**Commit:** `docs(study): document Simplified study mode` (code + tests ride in
S2/S3 per the repo's convention; S4 is docs plus any test-only follow-ups)

---

## Verification matrix

| Track | JVM (`/tmp/run-qemu-tests.sh`) | androidTest (CI only) | Extra |
|---|---|---|---|
| S1 | suite green (no new pure logic) | — | toggle flips and survives restart (manual) |
| S2 | `SpacedRepetitionTest` (mapping) + `RepositoryTest` (persist-on-Next) | — | — |
| S3 | suite green | `StudyAndSelectionUiTest` still passes unmodified (manual path untouched) | manual walkthrough of both modes on a device |
| S4 | full suite | — | — |

Baseline before starting: `OK (714 tests)`.

---

## Deliberately not in scope

| Dropped | Why |
|---|---|
| Hiding scheduler knobs in simplified mode | Visited once; the per-card decision was the complexity. Optional follow-up, not this feature. |
| Stronger guess scheduling (e.g. correct guess relearns in 10 min) | New scheduler semantics needing tests, migration story and export mapping. HARD already means "barely knew it". |
| Partial credit for multi-correct | Needs its own interval semantics. Exact match is honest under exam conditions. |
| Storing the guess flag | Its effect is fully delivered into `card_state` at grade time; nothing left to remember. |
| Retroactive "I guessed" after reveal | Defeats the pre-commit; the change-grade affordance covers misclicks. |
| Guess button in manual mode | Manual graders already express doubt via Hard. No second mechanism. |

---

## Progress log

| Track | Status | Commit | Notes |
|---|---|---|---|
| S1 — setting | ☑ done | `ff71a3d` | |
| S2 — logic + ViewModel | ☑ done | `7869bcd` | |
| S3 — screen | ☑ done | `a93bc34` | |
| S4 — tests + README | ☑ done | this commit | 12 mapping tests; `OK (723 tests)` |
