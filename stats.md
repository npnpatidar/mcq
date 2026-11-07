# Test redesign: one bounded Test feature, 20–150 questions, with Study-informed test types

Status: design settled; no code has been changed yet. This is the
prerequisite for the comprehensive-statistics work; the statistics plan is
preserved in [`statistics-plan.md`](statistics-plan.md).

**TL;DR.** A paper is a subject, a category is a topic, and a paper can hold
1,000+ questions. Test currently uses the *whole* scope as the *session size*,
so a 1,000-question paper becomes a 1,000-question test, and answering 20
questions stores 980 as skipped. The fix is to make Test a single, bounded
feature:

- **Merge Quick drill and Test.** One Test button, one setup sheet, one
  session shape, one history entry. The separate drill icon and dialog go
  away.
- **Every Test is 20–150 questions, in steps of 10.** Presets are
  `20 / 50 / 80 / 100 / 120 / 150`; the default is 20. A scope larger than
  150 is never tested whole.
- **Scope and length are separate.** Scope is the pool (whole paper or ticked
  topics); length is how many questions are actually sat.
- **Test types.** Representative tests (Random, Balanced) estimate mastery.
  Practice tests use Study-derived, read-only knowledge to target weakness:
  Weakest topics, Toughest questions, Easiest questions, Mistakes, Tricky,
  At-risk, Unseen. Custom tests cover bookmarks, tags and authored difficulty.
- **Test never writes Study state.** It writes only `attempts`,
  `question_results`, `question_stats` and `test_progress`. It may read
  `card_state` and `review_log`, but never changes them. FSRS scheduling is
  untouched.
- **Comparability is explicit.** Random and Balanced scores may be compared
  when the scope and length match. Practice scores are biased by design and
  must never share a trend line with mock scores.

## 1. The problem

A paper is a subject. A category is a topic. A topic can still hold hundreds
of questions. Today:

- `Test` with nothing ticked builds a session from **every question in the
  paper**.
- `Test` with categories ticked builds a session from **every question in
  those categories**.
- The start dialog asks only **how many minutes**. There is no question count.
- On submit, every question in the session is stored; unanswered ones are
  `skipped`.

So a 120-minute test of a 1,000-question paper, with 20 questions answered,
is stored as 20 correct/incorrect and 980 skipped, scored out of 1,000. That
is not a test result; it is a coverage report with a percentage attached. The
number is not comparable to another attempt, and a "latest vs best" trend
built from such attempts is noise.

The **Quick drill** already samples a scope to a requested count. It proves
the concept works, but it is a second-class path:

- a separate icon and dialog, labelled "Quick drill";
- defaulted to 10 questions / 5 minutes, which reads as a warm-up;
- uniform random, with no topic balance and no study-derived targeting;
- documented as capped at 1–50 questions and 1–180 minutes, but not capped in
  code;
- saved to History even though the README says drills are not saved;
- not distinguished from a full test in the attempt row.

The fix is not a new test engine. It is to merge the two paths and make a
**bounded, typed test** the only way to start a Test.

## 2. Decisions locked by the owner

| Decision | Value |
|---|---|
| Quick drill and Test | **Merged into one Test feature**; one entry point, one dialog, one session. |
| Minimum length | **20 questions.** |
| Length step | **10 questions**: 20, 30, 40, … 150. |
| Maximum length | **150 questions.** A scope larger than 150 is never tested whole. |
| Presets | **20, 50, 80, 100, 120, 150.** |
| Default | **20** (settled; section 16 #2). |
| Study-derived test types | Allowed, but **read-only**. Test must not change FSRS state. |
| Test/Study separation | Test writes only Test tables; Study scheduling never reads Test results. |

The old "All N questions" option is therefore **removed for scopes larger than
150**. For a scope of 150 or fewer, "all" is just the largest allowed length
and is labelled normally. There is no 1,000-question test.

## 3. Hard boundary: Test and Study are separate

Study is FSRS scheduling. Test is assessment. The two must not bleed into each
other.

**Test must not write Study state.**

- A test attempt writes `attempts`, `question_results`, `question_stats` and
  `test_progress` only. It must never insert/update `card_state`, append
  `review_log`, change due dates, stability, difficulty, intervals, lapses,
  leeches, or the study queue.
- A test question is not a "review". Answering it correctly does not make the
  card easier; answering it wrongly does not schedule it sooner.
- Test-only actions (reveal, flag, practice mode, skip, time) do not enter the
  FSRS replay.

**Test may read Study-derived signals to choose a better sample.**

- `card_state` answers "which questions are unseen, due, weak, at risk, or
  repeatedly lapsed?"
- `review_log` answers "which questions have a low pass rate, many lapses, or
  were marked as guesses?"
- `question_stats` answers "which questions were missed in past tests?"
- These are read-only inputs to a sampling policy. They never feed back into
  scheduling.

**Study does not read Test results for scheduling.** A mock-test score is not
a review grade. Combining study and test retention would need a shared event
log and is explicitly deferred.

**Statistics keeps the two families separate.** SRS tabs read the replay and
`card_state`; the Exam tab reads `attempts`/`question_results`. A study-derived
slice may be shown next to an exam slice, but the two are not merged into one
"retention" number.

**Write matrix**

| Table | Owner | Test reads | Test writes |
|---|---|---|---|
| `questions`, `options`, `correct_answers`, `categories` | bank | yes | no |
| `bookmarks` | learner | yes | no (test may not bookmark) |
| `card_state` | Study/FSRS | **yes, read-only** | **never** |
| `review_log` | Study/FSRS | **yes, read-only** | **never** |
| `question_stats` | Test | yes | yes |
| `attempts`, `question_results` | Test | yes | yes |
| `test_progress` | Test | yes | yes |
| Test snapshot (DataStore) | Test | yes | yes |

A regression test should pin the boundary: saving an attempt leaves
`card_state` and `review_log` row counts unchanged. This is cheap and protects
the rule for every future test type.

### 3.1 `question_stats` means "currently stored"

`question_stats` is an aggregate of the attempts that are still in the
database, not an eternal log. When an attempt is deleted, the affected
questions' counters are recomputed from the surviving `question_results` in the
same transaction, and `lastResultIsCorrect` / `lastFinishedAt` are recomputed
too.

Every attempt-delete path must do this:

| Path | What happens |
|---|---|
| History deletes one attempt (`HistoryStore.deleteAttempt`) | Recompute the aggregate for every question in that attempt. |
| Paper delete (`AttemptDao.deleteByPaper`, then `QuestionStatsDao.deleteAllForPaper`) | The paper's questions go too, so their stats are swept, not recomputed. |
| Category delete (`QuestionStatsDao.deleteAllForCategory`) | The category's questions go too, so their stats are swept. |
| Question delete (`QuestionStatsDao.deleteByQuestion`) | The row is dropped with the question. |
| Backup restore (`wipeAllTables` + import) | The table is emptied and rebuilt by `applyQuestionStats` from the imported results. |

When the last surviving result for a question is deleted, delete the inert
`question_stats` row (`totalAttempts == 0`). A row with `totalAttempts > 0` but
no answered result (`correctCount = wrongCount = 0`) is a skip-only row and
must be kept: it still records exposure. `gradedCount` counts skips as well
(section 9.2), so it is not zero on such a row. Queries already treat a
missing row as "no evidence".

Consequence: History's "hardest ever" cards change when an attempt is deleted.
That is correct: the evidence is gone. The label should say "hardest in stored
attempts", not "hardest ever".

### 3.2 Pre-v21 attempts are a documented limitation

Pre-v21 full-scope attempts wrote a skipped row for every question, so
`question_stats.skipCount / gradedCount` is close to 1 across the bank. This
makes `skip(q)` and the Tricky selector weak signals until enough bounded
attempts exist.

There is no backfill and no pre-v21 exclusion in this design. The limitation is
stated wherever `skip(q)` or Tricky is explained; it is not silently corrected
by special-casing old rows. The representative counters (`rep*`, section 9.2)
start at zero at migration and therefore describe only post-migration
Random/Balanced attempts; that is a consequence of "no backfill", not a
separate exclusion rule.

## 4. Evidence from the current code

| Where | What it does |
|---|---|
| `ui/library/LibraryScreen.kt` | Test icon: with nothing ticked calls `onStart()` (whole paper); with a ticked selection calls `onScopedTest(ticked)`. |
| `ui/library/LibraryDialogs.kt` → `TestDurationDialog` | The only parameter is minutes. |
| `ui/test/TestViewModel.kt` → `load()` | Session order = every question in the paper, or every question in the ticked categories. Only `drillCount > 0` cuts the order with `sampled`. |
| `ui/test/TestViewModel.kt` → `load()` | Time = drill minutes, else the picker's minutes, else the paper's `durationMinutes`. |
| `ui/test/TestViewModel.kt` → `submit()` | Saves the whole order; unanswered questions become `skipped`. |
| `data/repository/HistoryStore.kt` → `saveAttemptStreamed` | `totalQuestions = order.size`; one result row per question in the order. Writes only `attempts`, `question_results`, `question_stats`. |
| `ui/library/LibraryPaperCard.kt` | Separate drill dialog; defaults 10/5; validates only `count <= available`; fields accept four digits. |
| `domain/Drill.kt` → `checkCount` | Refuses `count > available`; no 50/180 cap. |
| `domain/SessionOrder.kt` → `sampled` | Uniform, block-aware cut; a passage block may overshoot the requested count. |
| `domain/Models.kt` | `Paper`, `CategoryNode`, `Attempt`; an attempt has no kind/scope/requested count. |
| `data/local/Entities.kt` | `attempts` stores paper, title, counts, score, duration, finishedAt only. |
| `domain/TestSnapshot.kt` | Resume carries `isDrill`, `drillRequested`, `drillSeed`; no test type or selected ids. |
| `ui/history/HistoryScreen.kt` | Per-paper trends mix attempts of every length and kind in one line. |
| `data/repository/QuestionStore.kt` → `getOrderForCategories` | Calls `flatCategoryOrder`, which only filters and sorts the ids passed. It does **not** expand a parent category's subtree; `CategorySelection.orderedIds` does that. |
| `domain/Scoring.kt` → `summarize` | Adds every graded question to `maxScore` (skips included) and subtracts negative marking only for wrong answers. A skip is not penalised. |
| `data/local/Daos.kt` → `getLatestStandings` | Picks `MAX(id)` per question (insertion order), not `attempts.finishedAt`; a merge import can make an old attempt look latest. |
| `README.md` | Claims drill results are not saved; `submit()` always saves them. |

The important point: `HistoryStore.saveAttemptStreamed` already respects the
Test/Study boundary. The redesign does not need to change that; it needs to
change what goes into `order`, and record what kind of test it was.

## 5. Root cause

The app conflates two different things:

- **Scope** — the pool a test draws from (paper, category subtree, mistakes,
  bookmarks, a tag).
- **Session size** — how many questions the learner actually sits.

Today scope doubles as session size. Time is the only independent parameter,
and time cannot turn a 1,000-question pool into a 20-question test. The drill
proves the missing concept works; it is just not the default.

## 6. What "better" means

The redesign should improve Test on four axes:

1. **Meaningful measurement.** A bounded random or balanced sample produces a
   score with a known margin, comparable to another test of the same scope and
   length.
2. **Useful practice.** Study-derived types spend test time on weak topics,
   tough questions, mistakes, leeches and at-risk material, instead of
   re-testing what is already known.
3. **Coverage.** Over many tests, the learner can cover the bank and see what
   has never been tested, without ever sitting a 1,000-question paper.
4. **Honest statistics.** History and Statistics know the test type, scope and
   length, so they never mix a 10-question warm-up, a 20-question practice set
   and a 100-question mock into one trend line.

Design principles:

- Length is explicit and bounded.
- Scope and length are separate.
- Representative and practice tests are different products, labelled as such.
- No silent truncation and no silent fill: the start screen and the result say
  what was asked and what was shown.
- Passages stay whole; the hard cap is 150.
- Sampling is deterministic and survives resume.
- Test reads Study knowledge but never writes it.
- Every attempt records enough metadata for Statistics to compare like with
  like.

## 7. The unified Test flow

### 7.1 Entry points

- **Library card → Test** opens the Test setup sheet.
- **Library card → Mistakes** (the existing badge/shortcut) opens the same
  sheet with the **Mistakes** type preselected. It is a shortcut, not a
  separate test engine.
- **Quick drill is removed.** The Test sheet's default 20-question random test
  is the quick drill. There is no separate drill icon, dialog, route or
  `drillCount`/`drillMinutes` UI.

Keeping a "quick start" is still possible inside the sheet: the primary button
can read "Start 20-question test" and start immediately with the defaults, so
the common case is two taps (Test → Start). The sheet is only required when
the learner wants a different type, scope, length or time.

### 7.2 Setup sheet

```
Test · Polity
Scope:  Whole paper · 1,000 questions          [Change]
        or: 3 topics · 240 questions

Type:   [Mock: Random]  [Mock: Balanced]
        [Practice: Weakest topics] [Practice: Toughest questions]
        [Warm-up: Easiest] [Practice: Mistakes] [Practice: Tricky]
        [Practice: At risk] [Coverage: Unseen]
        [Custom: Bookmarked] [Custom: Tag] [Custom: Authored difficulty]

Length: [20] [50] [80] [100] [120] [150]      − 20 +

        20 questions of 1,000 available

Time:   [20 min]  (1 min / question)   [Override]  [Untimed]

Preview: Random sample · 20 questions · 20 min · comparable

[Start test]
```

- The sheet is the single entry point for Test and Mistakes.
- A ticked category selection is shown as the scope. If nothing is ticked, the
  scope is the whole paper.
- "Change" reuses the card's category picker. The sheet may also say "Change
  the ticked categories on the card" and return; phase 1 does not need a new
  category tree inside the sheet.
- Type cards show a one-line description, the type's available pool, and a
  **Comparable** or **Practice** badge.
- Length chips are disabled when the scope or type cannot supply that many
  questions.
- Start is disabled when the scope has no questions, the type pool is empty,
  or the selected length is invalid.
- The preview line is the contract: it names the type, the requested length,
  the time and the comparability.

### 7.3 Length rules

- Allowed requested lengths: `20, 30, 40, …, 150`.
- Presets: `20, 50, 80, 100, 120, 150`.
- Custom control: a stepper that moves by 10, clamped to the allowed range.
- Default: **20** (section 16 #2). The last-used length may be remembered
  later; it is still clamped.
- `available` is the number of questions the **scope and type** can supply.
- `maxAllowed = if (available < 20) available else
  min(150, largest multiple of 10 <= available)`.
- If `available < 20`: see the short-test rule below; the whole scope is the
  length, even though it is not a multiple of 10.
- If `available` is 20–29: only 20 is selectable.
- If `available` is 137: the largest selectable length is 130.
- If `available == 0`: Start is disabled with a specific message.
- If the requested length exceeds `available`: show "Only N questions in this
  scope" and clamp to `maxAllowed`. Never start silently smaller.
- The requested length is a **target**. A passage block may push the shown
  count above it, but the shown count is **never allowed above 150**. The
  session header says "20 asked, 24 shown — passages kept whole".
- The sampler is exact-fit first: it finds the best block combination at or
  below the target (section 9.6). It overshoots only when the target cannot be
  reached exactly, and then only by the smallest block within the overshoot
  limit; a 19 + 130 → 149 jump is not allowed. The shown count is never above
  150.

**Short-test rule (settled; section 16 #1).** If the whole scope has fewer
than 20 questions, allow a **short test** of the whole scope, labelled
"Short test · 12 questions" and excluded from comparable mock trends. Do not
disable Test. A 12-question topic is a legitimate quiz even if it is not a
20-question test.

### 7.4 Time rules

- Default pace: **60 seconds per question**.
- `suggestedMinutes = max(1, ceil(count * secondsPerQuestion / 60))`.
- At the default pace, the time presets mirror the length presets:
  20 min, 50 min, 80 min, 100 min, 120 min, 150 min.
- The learner can override the duration. Editing it detaches it from later
  length changes; the sheet shows "custom" until reset.
- Untimed is explicit (blank/0), as today.
- The paper's `durationMinutes` describes the whole paper, not a sample, so it
  is **not** used as the default for a bounded test. It may be shown as a hint:
  `paperPace = paperMinutes * 60 / paper.totalQuestions`, clamped to a
  plausible 20–180 seconds, labelled "Paper pace: 72 s/question". If the
  computed pace is absurd (a 1,000-question paper with a 120-minute duration
  gives 7.2 s/question), do not offer it.
- Timer warnings, auto-submit and dwell tracking are unchanged.

### 7.5 Validation and empty states

- `available == 0` → "This scope has no questions."
- `available < 20` → allow a labelled **Short test · N questions** (section
  16 #1); it gets no margin and no comparable trend.
- The sampler returns fewer than 20 from a scope of at least 20 → also a
  labelled **Short test**; non-comparable, no margin, no trend (section 9.6).
  This is a real outcome, not a bug to hide.
- The sampler selects nothing because every passage is larger than the
  overshoot limit → refuse to start with a specific message and suggest a
  longer length; never start an empty session (section 9.6).
- Type pool empty → "No questions match this test type right now."
- Type pool smaller than the requested length → fill from the type's fallback
  ladder (section 9.7) and show the composition; do not silently start a
  different test.
- A passage block larger than 150 → that block can never be tested whole;
  show "This scope contains a passage of N questions; split it or choose a
  different scope."
- Start is disabled while the plan is invalid. The button label states the
  final plan: "Start 20-question test · 20 min".

### 7.6 Resume

A metric-based test cannot be reproduced from a seed alone: the ranking can
change between the start and the resume (a study review or another attempt
moves the metrics). The snapshot therefore stores:

- the test type and requested count;
- the scope (ticked category ids);
- the **selected question ids in presented order** (at most 150 plus a
  passage overshoot, so a few kilobytes);
- the seeds for option shuffle and any question shuffle;
- the sampling version;
- the targeted topic ids (Weakest topics/Mixed) and the exclusion setting.

On resume:

- Rebuild the order directly from the saved ids, dropping ids that no longer
  exist. Do not re-rank, re-sample or re-shuffle.
- If fewer than 20 ids remain, warn and offer "Resume with N questions" or
  "Start a new test".
- A snapshot from an older version (`isDrill`, `drillRequested`, no
  `testType`, `samplingVersion < 2`) is a **legacy snapshot**. Resume it as a
  labelled Short/Legacy session, or offer "Start a new test"; never silently
  start a 10-question Random test.
- `TestSnapshot.matches` must include the test type and requested count, not
  only paper + categories + drill shape.

### 7.7 What the learner sees

- Before: "Test → 120 minutes → 1,000 questions."
- After: "Test → Random · 20 questions · 20 min · from 1,000."
- Submitting stores `requestedCount = 20` and `totalQuestions = shown` (20,
  or a little more when a passage block overshoots), and records
  `testType = RANDOM` and `samplingVersion = 2`.
- A practice test says "Practice · Weakest topics · 50 questions · 50 min ·
  not comparable to a mock."
- There is no 1,000-question test.

## 8. Test type taxonomy

Two families, because they answer different questions:

- **Measurement (representative):** "How well do I know this scope?" The
  sample must be unbiased. Scores are comparable when scope and length match.
- **Practice (targeted):** "What should I work on?" The sample is deliberately
  biased toward weakness, mistakes, risk or novelty. Scores are not
  comparable to a mock.

| Type | Family | Pool | Main data | Comparable? | Best for |
|---|---|---|---|---|---|
| **Random** | measurement | all in scope | none | **yes** | An honest mock of the scope. |
| **Balanced** | measurement | all in scope, stratified by topic | category counts | **yes** (with other balanced tests) | A mock that cannot miss a whole topic. |
| **Weakest topics** | practice | top-K weakest categories | `question_stats`, `card_state` | no | Fixing the topics that cost the most marks. |
| **Toughest questions** | practice | highest toughness items | `question_stats`, `card_state`, `questions.difficulty` | no | Drilling the hardest individual items. |
| **Easiest questions** | warm-up | highest ease items | `question_stats`, `card_state`, authored difficulty | no | A confidence warm-up or a low-energy day. |
| **Mistakes** | practice | latest graded answer was wrong | `question_results` / `question_stats` | no | Clearing the mistake list. |
| **Tricky** | practice | leeches, lapses, guesses, skips | `card_state`, `review_log`, `question_stats` | no | Material that repeatedly slips. |
| **At risk** | practice | due/overdue or low retrievability | `card_state` | no | Testing what FSRS says is about to be forgotten. |
| **Unseen** | coverage | no schedule, new, or stale hash | `card_state` | no | Covering the bank and measuring prior knowledge. |
| **Bookmarked** | custom | bookmarks ∩ scope | `bookmarks` | no | Revising saved questions. |
| **Tag** | custom | questions carrying a tag | `questions.tags` | no | A topic or theme the category tree does not express. |
| **Authored difficulty** | custom | `questions.difficulty = EASY/MEDIUM/HARD` | bank metadata | no | Using the author's difficulty labels. |
| **Slowest** | custom | high median dwell in past tests | `question_results.dwellSeconds` | no | Time management and time sinks. |
| **Mixed practice** | practice (later) | weighted mix of the above | all read-only signals | no | A daily driver that balances weakness, risk and coverage. |

The first implementation should ship **Random** plus the focus types the owner
asked for (**Weakest topics, Toughest questions, Easiest questions**) and
**Mistakes** (already exists). Balanced, Tricky, At-risk, Unseen and the
custom types follow in phase 2/3.

### 8.1 Random

- **Purpose:** an unbiased estimate of mastery over the scope.
- **Pool:** every question in the scope.
- **Selection:** uniform, block-aware sample of `n`.
- **Fill:** none; the scope is the pool.
- **Label:** `Mock · random · comparable`, or `Mock · random · fresh sample`
  when recent-question exclusion is on (section 9.8).
- **Notes:** the score is an estimate, not a census. Show the sample size and
  the confidence band. Two strict random tests of the same scope and length
  are comparable; a 20-question and a 100-question test are not one trend
  line. A fresh sample is not comparable to a strict Random attempt; two fresh
  samples that use the same exclusion rule are comparable, but their margins
  stay approximate because each excludes a different set.

### 8.2 Balanced

- **Purpose:** a mock with guaranteed topic coverage and lower variance.
- **Pool:** every question in the scope.
- **Selection:** proportional allocation across categories (section 9.4),
  then a uniform sample inside each category. Interleave categories in the
  presented order so the test does not run in topic blocks; keep passages
  whole.
- **Fill:** if a category is smaller than its quota, cap it and redistribute
  the remainder.
- **Label:** `Mock · balanced · comparable`, or `Mock · balanced · fresh
  sample` when recent-question exclusion is on.
- **Notes:** proportional allocation estimates the same scope proportion as
  Random, with lower variance when topics differ in difficulty. It is not the
  same design as Random, so group the trend by type; do not silently mix the
  two lines. An **equal** allocation (same number per topic) is a different
  product: it maximises coverage but over-represents small topics, so its
  score is not a population estimate. Offer it later as "Coverage", not
  "Balanced".

### 8.3 Weakest topics (toughest categories)

- **Purpose:** spend the test on the topics that cost the most marks.
- **Pool:** the top-K categories by category weakness (section 9.3), where K
  is chosen from the requested length (default 3; "narrow" 1, "medium" 3,
  "broad" 5).
- **Selection:** allocate `n` across the selected categories proportional to
  their size, with at least one question per selected category, then sample
  within each. If the top category alone can fill `n`, still include a small
  share from the next categories when K > 1, so the test is not a single-topic
  drill.
- **Fill:** next-weakest categories, then random from the scope.
- **Label:** `Practice · weakest topics · not comparable`.
- **UI:** show the chosen topics and their observed accuracy, e.g.
  `Polity 42% · Economy 48% · History 51%`.
- **Notes:** this is the type the owner asked for as "toughest categories".
  "Toughest" here means the learner's weakest observed categories, not the
  author's difficulty labels. If there is no test or study evidence, fall back
  to authored difficulty, then random. The score is biased low by construction;
  track the category accuracy over time, not the overall practice score.

### 8.4 Toughest questions

- **Purpose:** drill the hardest individual items.
- **Pool:** every question with a toughness score, ranked descending.
- **Selection:** take the top `n`, with a per-category cap of
  `max(1, ceil(0.40 × n))` so one topic cannot consume the whole test. If the
  cap is infeasible (`cap × number_of_categories < n`), relax it to
  `ceil(n / number_of_categories)` and redistribute the remainder; never drop
  a selected category below one question. Optionally sample from the top `2n`
  with rank weighting, so repeated tests are not identical.
- **Fill:** authored `HARD` questions without enough evidence, then random.
- **Label:** `Practice · toughest questions · not comparable`.
- **Notes:** this is the most demoralising type; default it to practice mode
  with explanations visible, not strict exam mode. A question's toughness
  changes as the learner improves, so two "toughest" tests are not a fixed
  form and their scores are not comparable.

### 8.5 Easiest questions

- **Purpose:** warm-up, confidence, quick revision.
- **Pool:** questions with the highest ease score, excluding leeches and
  at-risk cards when alternatives exist.
- **Selection:** sample from the top `2n` by ease (not strictly the top `n`,
  to avoid the same set every time).
- **Fill:** medium-difficulty questions, then random.
- **Label:** `Warm-up · easiest · not comparable`.
- **Notes:** ceiling effects make this insensitive to improvement; never use
  it to measure progress. It is useful before a mock or after a bad day.

### 8.6 Mistakes

- **Purpose:** clear the mistake list.
- **Pool:** questions whose **latest graded, answered** test result was wrong,
  scoped to the paper/categories. This is the existing `Mistakes` rule: skips
  and ungraded rows are not evidence, and a later correct answer clears the
  question.
- **Selection:** most recently missed first, ordered by
  `attempts.finishedAt DESC, question_results.id DESC` (not `MAX(id)`); if
  there are more than `n`, sample among the most recent `2n`.
- **Fill:** Tricky, then Toughest, then Random.
- **Label:** `Practice · mistakes · not comparable`.
- **Notes:** do not use `question_stats.lastResultIsCorrect` alone: a skipped
  last result is stored as `isCorrect = false` and would leak skips into the
  mistake pool. Reuse `AttemptDao.getLatestStandings` /
  `Mistakes.mistakenIdsByPaper`, which already filter to graded, answered rows.

### 8.7 Tricky (leeches, lapses, guesses, skips)

- **Purpose:** target material that repeatedly slips.
- **Pool:** `card_state.leech = 1`, or a high lapse rate, or a high guess rate
  from `review_log.wasGuess`, or a high test skip rate.
- **Selection:** rank by a trickiness composite, take the top `n` with the
  same per-category cap as Toughest.
- **Fill:** Toughest, then Random.
- **Label:** `Practice · tricky · not comparable`.
- **Notes:** "leech" is a Study concept. Testing a leech does not clear the
  leech flag; only Study does. The test simply spends time on it.

### 8.8 At risk (due / low retrievability)

- **Purpose:** test what FSRS says is about to be forgotten.
- **Pool:** cards due now, non-new, with a matching `contentHash`; optionally
  cards whose predicted retrievability is below the desired retention even if
  not yet due.
- **Selection:** lowest retrievability first, or a random sample among the due
  pool if it is large.
- **Fill:** soonest due, then Unseen, then Random.
- **Label:** `Practice · at risk · not comparable`.
- **Notes:** this does **not** consume the review. The card stays due until
  Study reviews it. FSRS never sees the test. Use `FsrsScheduler.retention`
  for the predicted recall probability; treat a stale `contentHash` as new,
  not as at-risk.

### 8.9 Unseen

- **Purpose:** cover the bank and measure prior knowledge.
- **Pool:** questions with no `card_state` row, or a new state
  (`reps = 0 && dueAt = 0`), or a stale `contentHash` (the question was edited
  and Study will treat it as new).
- **Selection:** uniform random.
- **Fill:** least-recently-reviewed, then Random.
- **Label:** `Coverage · unseen · not comparable`.
- **Notes:** an unseen test measures first-exposure knowledge, not retention.
  It is a useful baseline and a coverage tool, not a mock.

### 8.10 Bookmarked

- **Purpose:** revise saved questions.
- **Pool:** `bookmarks` ∩ scope.
- **Selection:** random, or most recently bookmarked first.
- **Fill:** Random from the scope; show `12 bookmarked + 8 random`.
- **Label:** `Custom · bookmarked · not comparable`.

### 8.11 Tag

- **Purpose:** test a theme the category tree does not express (for example
  "current affairs", "formula-based", "diagram").
- **Pool:** questions carrying the selected tag(s), within the scope.
- **Selection:** random.
- **Fill:** Random; show the composition.
- **Label:** `Custom · tag: X · not comparable`.
- **Notes:** `questions.tags` is a comma-joined string and `tagsFolded` is for
  search. Exact tag filtering at scale needs a `question_tags(questionId, tag)`
  table or delimited matching; treat that as phase 3.

### 8.12 Authored difficulty

- **Purpose:** use the author's labels when learner evidence is thin.
- **Pool:** `questions.difficulty = EASY/MEDIUM/HARD`, within the scope.
- **Selection:** random within the chosen difficulty.
- **Fill:** adjacent difficulty, then Random.
- **Label:** `Custom · hard (authored) · not comparable`.
- **Notes:** authored difficulty is not learner difficulty. It is a useful
  fallback, not a substitute for `question_stats` or FSRS.

### 8.13 Slowest (time sinks)

- **Purpose:** find and practise the questions that eat time.
- **Pool:** questions with enough dwell samples; median dwell above a
  threshold (for example, the paper's 75th percentile).
- **Selection:** highest median dwell first.
- **Fill:** Toughest, then Random.
- **Label:** `Custom · slowest · not comparable`.
- **Notes:** dwell is Test-owned (`question_results.dwellSeconds`), but it is
  only meaningful after enough attempts. A slow question may be hard, badly
  worded, or just long; the label says "slowest", not "hardest".

### 8.14 Mixed practice (later)

- **Purpose:** a daily driver that balances weakness, risk and coverage.
- **Composition (example):** 40% Weakest topics, 25% Mistakes/Tricky, 20%
  At-risk, 15% Unseen.
- **Selection:** allocate by composition, then by each type's selector.
- **Label:** `Practice · mixed · not comparable`.
- **Notes:** this is the most useful long-term practice mode, but it needs
  the individual selectors and a clear composition UI first.

## 9. Ranking and sampling algorithms

### 9.1 Data sources

| Signal | Source | Owner | Notes |
|---|---|---|---|
| Test accuracy (all attempts) | `question_stats.correctCount / (correctCount + wrongCount)` | Test | Exclude skips. `gradedCount` includes skips. Personal context. |
| Representative accuracy | `question_stats.repCorrect / (repCorrect + repWrong)` | Test | Only Random/Balanced with `samplingVersion >= 2`; mock context. |
| Skip rate | `skipCount / gradedCount` | Test | All attempts, including legacy. Weak signal until bounded attempts exist (section 3.2). |
| Last test result | latest `question_results` graded, answered row | Test | Use the standing rule, not `lastResultIsCorrect` alone. |
| Test exposure | count of `question_results` rows per question | Test | For repeat avoidance and coverage. |
| Dwell | `question_results.dwellSeconds` | Test | Median is more robust than mean. |
| FSRS difficulty | `card_state.difficulty` (1..10) | Study, read-only | Only valid when `contentHash` matches. |
| FSRS stability | `card_state.stability` (days) | Study, read-only | Only valid when `contentHash` matches. |
| Retrievability | `FsrsScheduler.retention(state, now)` | Study, read-only | Predicted recall probability. |
| Lapses / leech | `card_state.lapses`, `card_state.leech` | Study, read-only | Leech is a Study flag. |
| Guess rate | `review_log.wasGuess` | Study, read-only | Null on pre-v19 rows; ignore nulls. |
| Due date | `card_state.dueAt` | Study, read-only | Test never changes it. |
| Unseen | no `card_state` row, new state, or stale hash | Study, read-only | Stale = question edited. |
| Bookmark | `bookmarks` | Learner | Read-only for Test. |
| Tags | `questions.tags` | Bank | Exact filtering needs an index/table. |
| Authored difficulty | `questions.difficulty` | Bank | Fallback signal. |

### 9.2 Per-question metrics

Let `answered = correct + wrong` (skips excluded). Let `graded = correct +
wrong + skip` (what `question_stats.gradedCount` stores). Let
`repAnswered = repCorrect + repWrong` and `repGraded = repCorrect + repWrong +
repSkip`, from the representative counters (section 12.3). The `rep*` counters
are updated only for `RANDOM`/`BALANCED` attempts with
`samplingVersion >= 2`.

**Accuracy with a small-sample guard.** A question answered once and missed
once is not necessarily harder than a question missed 8 times out of 10. Use
the Wilson lower bound:

```
wilsonLower(p, n, z = 1.96) =
  (p + z²/(2n) − z·sqrt(p(1−p)/n + z²/(4n²))) / (1 + z²/n)
```

- `weakness(q)` for a **representative** ranking uses
  `1 − wilsonLower(repCorrect / repAnswered, repAnswered)` when
  `repAnswered >= 3`; for a **personal** focus ranking it uses
  `1 − wilsonLower(correct / answered, answered)` when `answered >= 3`.
  Otherwise `0.5` (neutral).
- `skip(q) = skipCount / gradedCount` when `graded >= 3`; otherwise `0.5`.
  This uses all-attempt counters; legacy full-scope attempts inflate it
  (section 3.2). Do not special-case them.
- `guess(q) = guesses / reviewsWithGuessFlag` when `reviewsWithGuessFlag >= 3`;
  otherwise `0`.
- `lapse(q) = min(1, lapses / max(reviewCount, 1) × 2)`; `1` if
  `card_state.leech = true`.
- `fsrsDifficulty(q) = (card_state.difficulty − 1) / 9` when the card has
  valid FSRS memory (`stability > 0`, `reviewCount > 0`, state is not `NEW`,
  and `contentHash` matches); otherwise unavailable.
- `authoredDifficulty(q)` = `EASY = 0`, `MEDIUM = 0.5`, `HARD = 1`.
- `difficultyEvidence(q) = min(1.0, card_state.reviewCount / 5.0)` — how much
  the FSRS estimate should be trusted. One review must not dominate.
- `difficulty(q) = difficultyEvidence × fsrsDifficulty
  + (1 − difficultyEvidence) × authoredDifficulty` — a confidence-weighted
  blend, not a raw FSRS value.
- `memoryRisk(q) = 1 − retention(card, now)` when the card has valid memory;
  otherwise `0.5`.
- `unseen(q) = true` when there is no card, the card is new, or the stored
  `contentHash` no longer matches the question.
- `stale(q) = true` when the card exists but its `contentHash` no longer
  matches; treat memory signals as invalid and the card as new.

**Composite toughness** (weights are heuristics, not fitted constants):

```
toughness =
    0.35 × weakness
  + 0.20 × difficulty
  + 0.20 × memoryRisk
  + 0.15 × lapse
  + 0.10 × skip
```

**Composite ease** (for warm-up; exclude leeches and at-risk when possible):

```
ease =
    0.45 × (1 − weakness)
  + 0.25 × (1 − difficulty)
  + 0.20 × (1 − memoryRisk)
  + 0.10 × (1 − lapse)
```

**Category weakness** (for Weakest topics):

```
categoryWeakness =
    0.50 × (1 − wilsonLower(catCorrect / catAnswered, catAnswered))
  + 0.25 × mean(difficulty of the category's questions)
  + 0.15 × mean(memoryRisk)
  + 0.10 × mean(lapse)
```

- Use a neutral `0.5` for any component with no evidence, so an unseen
  category is not ranked as "easy" just because it has no data.
- Rank by the lower confidence bound, not the raw rate, so a 1-of-2 category
  does not beat a 4-of-10 category by luck.
- The weights should live in one pure object (`QuestionSignals` or
  `TestRanking`) with JVM tests, not scattered through SQL.

**Which counter set a ranking reads**

| Ranking | Counter set | Why |
|---|---|---|
| Random/Balanced weakness and mock report | `rep*` | Representative attempts only; practice must not contaminate measurement. |
| Weakest topics, Toughest, Easiest, Mistakes, Tricky | all-attempt counters | Personal difficulty; practice evidence is allowed (decision 7). |
| Per-question lifetime accuracy in a mock report | `rep*` | Matches the report context. |
| Per-question lifetime accuracy in a practice report | all-attempt counters | Matches the practice context. |

The report must label the source, because the same question can show two
different "3/7" values in a mock and a practice report.

### 9.2.1 FSRS difficulty as a prior (read-only)

Yes, `card_state.difficulty` is useful guidance — but it is a **prior**, not a
test score and not a stable item parameter. Use it carefully.

**What it is.** FSRS's 1–10 estimate of how hard the card is *for this
learner*, updated only by Study grades. It is exactly the kind of Study-derived
knowledge Test may read.

**What it is not.**

- Not an intrinsic difficulty of the question: it is learner-specific and
  changes as the learner improves.
- Not a substitute for test accuracy: it measures recall difficulty in Study,
  not performance on a timed MCQ.
- Not available for an unseen question, a new card, or a stale card whose
  `contentHash` no longer matches its question.
- Not reliable after one review. The first grade sets an initial difficulty;
  a single Hard or Again can move it a lot.

**Validity gate.** Use FSRS difficulty only when all of these hold:

```
card_state.stability > 0
card_state.reviewCount > 0
card_state.cardState != NEW
card_state.contentHash == questions.contentHash
```

Otherwise `fsrsDifficulty` is unavailable, and the composite falls back to
the authored label (`EASY/MEDIUM/HARD`) with neutral evidence.

**Confidence weighting.** Blend the FSRS estimate with the authored label by
how many Study reviews back it:

```
evidence = min(1.0, reviewCount / 5.0)
difficulty = evidence × fsrsDifficulty + (1 − evidence) × authoredDifficulty
```

A card reviewed once is mostly its authored label; a card reviewed five or
more times is mostly its FSRS estimate. This prevents a single grade from
reordering the whole "toughest" list.

**Where it helps**

| Test type | How FSRS difficulty is used |
|---|---|
| **Toughest questions** | A 0.20-weight prior in the toughness composite. Test accuracy remains the primary signal (0.35). |
| **Weakest topics** | Category mean of the confidence-weighted difficulty, as 0.25 of category weakness. Helps rank topics with little test evidence. |
| **Easiest questions** | Low difficulty with high evidence; otherwise fall back to authored `EASY`. |
| **Tricky** | High difficulty plus lapses/leeches is a strong "repeatedly slips" signal. |
| **At risk** | Modulates risk: high difficulty + low stability + overdue is worse than low difficulty + the same schedule. Stability and due date remain the primary at-risk signals. |
| **Mistakes** | A tie-breaker/priority among mistakes: the harder ones first. The mistake standing itself still comes from test results. |
| **Unseen** | Not available by definition; use authored difficulty or neutral. |
| **Random / Balanced** | **Do not use it to select questions.** A representative mock must stay unbiased; FSRS difficulty may be *reported* next to the result, but it must not choose the sample. |
| **Custom (tag, bookmarks, authored difficulty)** | Optional tie-breaker only. |

**Why not use it in Random/Balanced.** If "tough" cards are over-sampled in a
mock, the score is no longer an estimate of the scope. Stratified sampling by
difficulty with proportional allocation would still be unbiased in theory, but
it adds complexity and a mis-specified allocation would bias the score. Keep
measurement simple: Random samples uniformly; Balanced stratifies by topic,
not by difficulty.

**A useful diagnostic: the memory-vs-performance gap.** Comparing FSRS
difficulty (or predicted retrievability) with test accuracy tells you *why* a
question is failing:

| FSRS says | Test says | Interpretation | Action |
|---|---|---|---|
| hard | low accuracy | genuine weakness | prioritise; study then retest |
| hard | high accuracy | recognition/format is easier than free recall, or recent study inflated the test | do not assume mastery; test again later |
| easy | low accuracy | application, wording, careless error, or time pressure | inspect the explanation and dwell time; this is not a memory problem |
| easy | high accuracy | consolidated | deprioritise |

Show this as a quadrant/scatter in Statistics, labelled "Study estimate" vs
"Test accuracy". It is guidance, not a single mastery score.

**Guardrails**

- Read-only. Test never writes `card_state` or `review_log`.
- A stale `contentHash` invalidates the memory estimate; treat the card as new.
- A new card has no FSRS difficulty; do not invent one.
- Weight by `reviewCount`; do not let one grade dominate.
- Never use FSRS difficulty to select a representative mock sample.
- Label it as a Study estimate wherever it is shown.
- It can drift over time; it is a prior, not ground truth.

### 9.3 Category selection

Before scoring, expand the scope once with `CategorySelection.orderedIds`: a
ticked parent includes its whole subtree, and a parent plus its child is
deduped to the parent. Compute quotas on **every node in the expanded set**,
parents included, each with its own questions. The DAO matches
`questions.categoryId IN (...)`, and a question has exactly one `categoryId`,
so passing a parent together with its child matches each question once;
dropping the parent id would lose the questions attached directly to it.

For **Weakest topics**:

1. Score every category in the scope with `categoryWeakness`.
2. Sort ascending (weakest first), breaking ties by more answered questions,
   then by title.
3. Choose `K`:
   - narrow: 1 category;
   - medium: 3 categories (default);
   - broad: 5 categories.
   Cap `K` so the selected categories can supply `n` questions; if they cannot,
   add the next-weakest categories.
4. Allocate `n` across the selected categories (section 9.4), with at least
   one question per selected category.
5. Sample within each category, then interleave the selected blocks so the
   test is not a single-topic run.

If the learner wants a pure single-topic drill, "narrow" gives that. If they
want breadth, "broad" gives that. The default 3 is a compromise.

### 9.4 Allocation: largest remainder with caps

For `n` questions across categories with counts `c_i` and total `N`:

```
quota_i = n × c_i / N
floor_i = floor(quota_i)
remainder_i = quota_i − floor_i
assigned_i = min(floor_i, c_i)
left = n − sum(assigned_i)

# give one to the largest remainders first
while left > 0 and any category has room:
    pick the category with the largest remainder that still has room
    assigned_i += 1
    left -= 1

# if a category was capped, redistribute its excess the same way
```

- If `n >= number of categories`, guarantee at least 1 per category (subject
  to room), then allocate the rest proportionally.
- If `n < number of categories`, allocate to the largest categories first;
  small categories are not sampled in this test. A later "rotating balanced"
  mode can rotate which small categories are included across tests.
- For **equal coverage** (not "Balanced"), replace `quota_i` with `n / K` for
  the selected `K` categories. This maximises topic coverage but over-represents
  small topics, so label it "Coverage" and do not compare its score to a
  proportional mock.

Example: `n = 50`, categories A = 600, B = 300, C = 100, N = 1,000.
Quotas are 30, 15, 5; floors are 30, 15, 5; no remainder. The test has 30 A,
15 B, 5 C, matching the bank.

Example: `n = 20`, eight equal categories, N = 800. Quotas are 2.5 each;
floors are 2 each (16 total), remainders are 0.5 each; four categories get one
extra, so four categories get 3 and four get 2.

**40% category cap (Toughest/Tricky).** Let `cap = max(1, ceil(0.40 × n))`.
If `cap × K < n`, the cap is infeasible; relax it to `ceil(n / K)` and
redistribute the remainder. If a category has fewer than its cap, cap it at
its size and redistribute by largest remainder. Example: `n = 20` with two
categories gives `cap = 8` each (16), so relax to 10 each.

### 9.5 Within-stratum sampling

- Expand the scope once with `CategorySelection.orderedIds` and use the full
  resulting set. `getOrderForCategories` does not expand subtrees, so pass the
  whole expanded list; the query is `categoryId IN (...)`, a question has one
  `categoryId`, and a parent plus its child therefore matches each question
  once. Build one `SessionOrder` per node in the expanded set, parents
  included, or a category-aware builder that carries the category index per
  row.
- Derive a per-category seed from `(sessionSeed, categoryId)` so the sample is
  deterministic and independent across categories.
- Sample `assigned_i` questions inside the category with the block-aware
  sampler.
- Concatenate the selected blocks and shuffle them globally with the question
  seed, so topics interleave but passages stay contiguous.
- For top-`n` selectors (Toughest, Easiest, Mistakes, Tricky), select ids
  first, resolve their `SessionRef`s in one chunked query, build
  `SessionOrder.of(refs)`, then shuffle if enabled.

### 9.6 Passage blocks and the hard cap

Passages are indivisible. A block is a passage's members plus any singleton
question. The current `SessionOrder.sampled` takes whole blocks and may
overshoot the requested count. That is fine for a drill, but it violates the
150 cap if a block pushes past 150.

Use a new **bounded block sampler**:

```
sampledBounded(target, max = 150, seed):
    blocks = shuffleBlocks(scope, seed)      # whole passages, authored order inside
    # pass 1: exact-fit DP over the shuffled blocks, capacity = target
    reachable = bitset(0)                    # 151 bits is enough
    predSum = map<sum, previousSum>
    predBlock = map<sum, block>
    for block in blocks:
        for s from target - block.size down to 0:
            if reachable[s] and not reachable[s + block.size]:
                reachable[s + block.size] = true
                predSum[s + block.size] = s
                predBlock[s + block.size] = block
    best = highest reachable sum <= target
    selected = reconstruct(best)             # earlier block wins on a tie
    # pass 2: if short, add the smallest remaining block within the limit
    if best < target:
        limit = min(max, target + max(1, ceil(0.25 * target)))
        candidates = remaining blocks with best + size <= limit
        if candidates is not empty:
            add the smallest candidate (tie-break: earlier in shuffled order)
    if selected is empty:
        if any block.size <= max:
            error "No passage fits the requested length and overshoot limit; choose a longer test or split a passage."
        else:
            error "Every passage is larger than 150; split one or change the scope."
    return selected
```

Rules:

- Never exceed `max = 150`.
- **Shuffle first, then DP.** The DP is deterministic over the shuffled order,
  so exactness does not cost randomness. The tie-break prefers the earlier
  block in shuffled order.
- Prefer exact fit; the DP finds it whenever a subset of blocks sums to the
  target. This is why a greedy first pass is not enough: blocks of 6, 5 and 5
  must reach 10 as 5 + 5, not stop at 6.
- Overshoot only when the target cannot be reached exactly, and only within
  the limit `max(1, ceil(0.25 × target))` above the target. A 19 + 130 case
  cannot produce 149; if the only remaining block is 130, stop at 19 and
  label the attempt "19 shown (short)".
- If a single block is larger than `max`, that block can never be tested
  whole. Show a specific error and let the learner split the passage or change
  the scope.
- If the result is below the target, accept it and label "N shown". If the
  shown count is below 20 while the scope has at least 20, the attempt is a
  **Short test** (section 16 #1): non-comparable, no margin, no trend. The
  19 + 130 case above is exactly this outcome; do not label it a sampler bug.
- If pass 1 and pass 2 select nothing, refuse to start with a specific
  message; never return an empty session. A scope made only of 30-question
  passages with a 20-question target refuses and suggests a 30-question test;
  a block larger than 150 gets the passage-too-large message. This is a
  validation failure, not a Short test.
- The session header says "20 asked, 24 shown — passages kept whole" whenever
  `shown != requested`.

This replaces the "always overshoot" behavior for Test. Study does not sample,
so it is unaffected. The old `sampled` can remain for legacy snapshots or be
folded into `sampledBounded`.

### 9.7 Fill ladder

When a type's pool is smaller than the requested length, fill in a defined
order and show the composition. Never silently substitute a different test.

| Type | Fill order |
|---|---|
| Random | none (the scope is the pool) |
| Balanced | next categories by size, then random |
| Weakest topics | next-weakest categories, then random |
| Toughest questions | authored `HARD`, then random |
| Easiest questions | authored `MEDIUM`, then random |
| Mistakes | Tricky, then Toughest, then Random |
| Tricky | Toughest, then Random |
| At risk | soonest due, then Unseen, then Random |
| Unseen | least recently reviewed, then Random |
| Bookmarked | Random |
| Tag | Random |
| Authored difficulty | adjacent difficulty, then Random |
| Slowest | Toughest, then Random |
| Mixed practice | its component types' ladders |

The preview and the result should say `12 mistakes + 8 toughest` or
`12 bookmarked + 8 random`, so the learner knows the test was not purely the
chosen type. A focus test with a large random fill is closer to a random test
with a weak-topic head start; label it accordingly.

### 9.8 Repeat avoidance and coverage

- For Random, Balanced and Easiest, exclude questions asked in the last **3
  comparable** attempts of the same paper when enough alternatives exist.
  "Comparable" here means the same paper, type family, scope key and length;
  practice attempts never count, so a burst of practice cannot empty the
  window. Define "enough" as `remaining >= 2 × requested`; otherwise skip the
  exclusion rather than distort a small pool.
- The setting ("Avoid recently tested questions", default **on**) is recorded
  on the attempt as `exclusionMode` (`NONE` or `RECENT_3`). It is part of the
  comparability key (section 11.5).
- An attempt that uses exclusion is a **Fresh sample**. It is not comparable to
  a strict Random/Balanced attempt. Two fresh samples that use the same rule
  are comparable, but their margins are approximate because each excludes a
  different set; the trend legend must say so.
- The exclusion is a deliberate deviation from uniform sampling. It is a
  product choice, not a measurement design; the label and the comparability
  key carry that fact.
- For a dedicated **Coverage** mode (later), weight selection inversely to the
  question's test exposure. That is a biased sample for score estimation, so
  label it "Coverage", not "Random".
- Track distinct questions tested / scope size and repeat rate for
  Statistics. For old huge attempts, count a question as tested only when it
  was engaged (`selectedOptionIds != ''` or `dwellSeconds > 0`), not when it
  was merely present as a skipped row.

### 9.9 Determinism, resume and metadata

- Random/Balanced: a seed reproduces the sample, but the snapshot stores the
  selected ids anyway, so a bank edit or a category-count change cannot alter
  a resumed session.
- Metric-based types: the selected ids are the only reliable reproduction;
  store them in presented order.
- Store `testType` (nullable; `NULL` = legacy), `requestedCount` (0 = legacy),
  `scopeCategoryIds` (ticked ids for display), `scopeKey` (canonical),
  `samplingVersion` (new attempts write **2**), `seed`, `exclusionMode`,
  `targetTopicIds` and (for focus fills) `compositionJson`.
- The canonical `scopeKey` is the subtree-expanded, deduplicated, sorted
  category id list, joined with a separator and hashed; the whole paper gets a
  distinct constant. Identical scopes must produce the same key.
- `TestSnapshot` adds `testType` (nullable), `requestedCount`,
  `selectedQuestionIds`, `samplingVersion`, `exclusionMode` and
  `targetTopicIds`. Old snapshots have `testType = null` and
  `samplingVersion = 1`; they are legacy, not Random.
- `matches()` includes the type, count, exclusion mode, expanded `scopeKey`
  and target set. A Weakest-topics snapshot must not be offered to a Random
  test of the same scope, and vice versa.

### 9.10 Performance and scale

- Selection reads ids, category ids and passage ids only; never question
  content, stems, options or explanations.
- The final selected set is at most 150 questions (plus a passage block), so
  the session order is tiny. `countUngraded` can always use the id list, not a
  whole-paper aggregate.
- Random/Balanced: building per-category `SessionOrder`s is O(N) in ids, the
  same order of work the app already does for a full session. The int arrays
  are ~4 bytes per question; a million-question paper is ~4 MB per order, and
  per-category orders sum to the same total.
- Focus rankings: do the heavy ranking in SQL with `ORDER BY` + `LIMIT` and
  the new indexes; avoid loading every question's stats into Kotlin. For a
  million-question paper, rank per category and merge the top candidates, or
  materialise a per-paper priority cache later.
- Add indexes before shipping the focus types:
  - `question_stats(paperId)` (no `lastFinishedAt` index until a query needs
    one)
  - a **partial** `question_results(questionId, id)` for graded, answered rows
  - `attempts(paperId, testType, requestedCount, scopeKey, samplingVersion,
    exclusionMode, finishedAt)` for comparable trends
  - `card_state(paperId, leech, dueAt)` for leech/at-risk queries
  - see section 12.3 for why the partial index needs a migration plus a
    `RoomDatabase.Callback.onCreate` path.
- Tag filtering is the one place a scan is unavoidable with the current
  schema; defer it or add a `question_tags` table.

## 10. Statistical rationale

A test score is an estimate of mastery. For a random sample of `n` questions,
the approximate 95% margin at `p ≈ 0.5` is `1.96 × 0.5 / sqrt(n) ≈ 0.98 /
sqrt(n)`:

| Requested n | Approx. 95% margin |
|---|---|
| 20 | ±22 points |
| 30 | ±18 points |
| 40 | ±15.5 points |
| 50 | ±14 points |
| 80 | ±11 points |
| 100 | ±10 points |
| 120 | ±9 points |
| 150 | ±8 points |

(Independent random sampling. Passages are clusters, not independent
questions, so the real margin is somewhat wider; label the figure
"approximate" and, in phase 2, bootstrap by resampling passages rather than
questions. MCQ guessing compresses the observed spread, so this is a
conservative planning figure, not a promise.)

**Finite population correction.** When the sample is a large fraction of a
small scope, the margin shrinks by `sqrt((N − n) / (N − 1))`. For a 1,000-
question scope and `n = 150`, the factor is 0.92, so ±8 becomes about ±7.4.
For a 100-question topic and `n = 20`, the factor is 0.90, so ±22 becomes about
±20. The correction matters for topic-sized scopes, not for a 1,000-question
subject.

The margin uses the **shown** count `n`, while comparability uses the
**requested** count. If a passage block overshoots, report the shown count and
say the margin is approximate.

**Comparing two tests.** The difference between two independent scores has a
standard error of `sqrt(se1² + se2²)`. A 20-question test cannot reliably
detect a 5-point improvement; even a 150-question test has a minimum
detectable difference of roughly 11 points at 95% confidence. The Exam tab
must show the sample size and a confidence band, and must not compute a
"latest minus first" delta across mixed lengths without a caveat. Paired
designs (the same questions before and after) are more sensitive, but they are
a different product and need a fixed question set.

**Why 20 is the minimum.** Below 20, the margin is wider than 22 points; a
10-question drill can swing 30 points on luck. A 20-question test is the
smallest defensible "test" label; 50+ is where a trend line starts to mean
something.

**Why 150 is the maximum.** The margin is about ±8 points, the sitting is
about 2.5 hours at one minute per question, and it aligns with real exam
lengths (UPSC Prelims GS is 100 questions in 120 minutes; NEET UG is 180
questions in 180 minutes). Beyond 150, fatigue and item exposure dominate the
statistical gain. A 1,000-question "test" is outside every real pattern.

**Guessing.** For a single-correct question with `k` options, the
chance-corrected score is `(p − 1/k) / (1 − 1/k)`. At `k = 4`, a raw 50% is
33% above chance. Multi-correct questions and negative marking complicate the
correction; show the raw score as the headline and the corrected score as a
secondary line in Statistics, clearly labelled.

**Practice tests are biased by design.** A weak-topic test will score lower
than a random test; that is the point. Two statistical traps:

- **Regression to the mean.** Selecting the lowest-performing topics or
  questions and retesting them will usually show improvement even if no
  learning happened. Do not read a focus-test gain as proof of learning
  without a random control sample.
- **Changing pools.** "Toughest questions" is a different set each time the
  metrics change, so two toughest-question scores are not comparable.
  Weakest-topics runs also target different topics. For a per-topic trend,
  store `targetTopicIds` with each attempt and compare only runs with the same
  recorded target set; a run with empty targets cannot contribute. Otherwise
  use random/balanced mocks or a fixed panel of questions.

**Legacy skip signal (documented limitation).** Pre-v21 full-scope attempts
inflate `skipCount`, so `skip(q)` and Tricky are weak signals until enough
bounded attempts exist. There is no backfill and no pre-v21 exclusion; see
section 3.2.

**`question_stats` is "currently stored".** Deleting an attempt recomputes the
affected rows, so History's "hardest" cards change when the evidence is
deleted. That is correct; see section 3.1.

**Reliability and item quality (later).** With enough attempts, split each
test into odd/even halves, correlate the two half-scores, and apply
Spearman–Brown to estimate full-test reliability; the standard error of
measurement is `SD × sqrt(1 − reliability)`. Item difficulty (`p` = proportion
correct) and discrimination (point-biserial correlation between item
correctness and total score) can flag ambiguous or broken questions. These are
Statistics features, not Test blockers, and need a reasonable number of
attempts before they mean anything.

**Adaptive testing (later).** IRT/CAT would pick the next question from an
ability estimate and item parameters. The app has no multi-learner calibration
data, so a true CAT is not feasible yet. A heuristic "staged difficulty" test
(easy → medium → hard, or adapt on a rolling accuracy) is possible later, but
its score is not comparable to a fixed-form mock.

**What the evidence says about practice design.** Retrieval practice (the
testing effect) is stronger than rereading; spaced practice beats massed
practice; interleaving topics improves discrimination and transfer; and
"desirable difficulty" means practice should be hard enough to require effort
but not so hard that it demoralises. That is the rationale for the type
families: Random/Balanced for measurement, Weakest/Toughest/Tricky for
desirable difficulty, Easiest for warm-up, and interleaved ordering even
inside a focus test.

## 11. The results page: evidence, meaning and next actions

The current Results screen is a score header, a category tally and a paged
list of question cards. That is enough to see *what* happened, but not *what
it means* or *what to do next*. A bounded, typed Test needs a report that
turns the attempt into evidence and a short action plan.

The report is part of Test, not Study. It reads `card_state` for the
memory-vs-performance view, but it never writes it. It must say so on the page.

### 11.1 Goals

1. **How did I do?** Score, accuracy, correct/wrong/skipped/ungraded, time.
2. **How much can I trust it?** Sample size, margin, comparability, chance
   correction, negative marking.
3. **What does it mean?** Topic breakdown, difficulty/memory gap, time
   behaviour, error mix.
4. **What changed?** Mistakes added/cleared, coverage, repeats, due-in-Study
   count.
5. **What should I do next?** Three to five evidence-backed actions with
   links to Study, a new Test, or the question review.
6. **What did it not do?** It did not change the FSRS schedule.

### 11.2 Page structure

```
┌ Test result ────────────────────────────────────────────┐
│ Mock · Random · 20 of 1,000 questions · 20 min           │
│ 12 correct · 4 wrong · 2 skipped · 2 ungraded · 59.3%    │
│ [Mock — comparable]                                      │
│ This test did not change your Study schedule.            │
└──────────────────────────────────────────────────────────┘
┌ What this result means ──────────────────────────────────┐
│ Answered 18/20 · 12 correct · 4 wrong · 2 skipped        │
│ Accuracy 67% · score 59.3% (4 wrong × 1/3 marking;       │
│ skips are not penalised)                                 │
│ 95% margin ±22 points · 20-question sample               │
│ Last comparable: 55% (+4, inside the margin)             │
└──────────────────────────────────────────────────────────┘
┌ Where the marks went ────────────────────────────────────┐
│ Knowledge gap 3 · Rushed 1 · Skipped 2 · Ungraded 2      │
│ Fragile correct 2 · Solid correct 10                     │
│ [Review the 4 wrong questions]                           │
└──────────────────────────────────────────────────────────┘
┌ Topics ──────────────────────────────────────────────────┐
│ Polity 5/12 42% (scope 60% asked) · Economy 4/5 80%      │
│ History 3/3 100% · 3 topics in scope were not sampled    │
└──────────────────────────────────────────────────────────┘
┌ Difficulty & memory ─────────────────────────────────────┐
│ Study-hard / wrong 3 · Study-easy / wrong 1              │
│ Study-hard / correct 2 · Study-easy / correct 8          │
│ 6 questions have no Study history yet                    │
└──────────────────────────────────────────────────────────┘
┌ Time ────────────────────────────────────────────────────┐
│ 20 min · median 48 s/question · target 60 s              │
│ Wrong answers averaged 82 s; correct answers 44 s        │
│ Time sinks: Q4 2:10, Q11 1:45, Q17 1:30                  │
└──────────────────────────────────────────────────────────┘
┌ Coverage & progress ─────────────────────────────────────┐
│ 180/1,000 questions tested (18%) · 6 repeats this test   │
│ Mistakes: 7 added · 3 cleared · 4 due in Study           │
└──────────────────────────────────────────────────────────┘
┌ What to do next ─────────────────────────────────────────┐
│ 1. Study Polity (5/12, 42%)                      [Study] │
│ 2. Review the 4 wrong questions                 [Review] │
│ 3. Take a 50-question mock for a tighter estimate [Test] │
└──────────────────────────────────────────────────────────┘
┌ Questions ───────────────────────────────────────────────┐
│ [All] [Wrong] [Skipped] [Slow] [Possible guess] [Saved]  │
│ ... paged question review with per-question evidence ...  │
└──────────────────────────────────────────────────────────┘
```

The page is a `LazyColumn` of sections. The top three actions are always
visible; the deeper evidence is expandable so the first screen is not a wall
of numbers. On a phone, each section is a card with a one-line summary and an
expand affordance.

The block above is a **mock** result, because it shows a margin and a
comparable trend. A practice result replaces those with the target-accuracy
line and never shows a mock trend (section 11.3).

### 11.3 The headline changes with the test family

A mock and a practice set are different products, so the headline is
different.

| Family | Headline | Secondary |
|---|---|---|
| **Random / Balanced mock (strict)** | Score and percentage | Margin, last comparable reference (line only when trend-worthy), weakest topics, time |
| **Random / Balanced (fresh sample)** | Score and percentage + "Fresh sample" | Approximate margin, fresh-vs-fresh trend only; never compared to strict |
| **Weakest topics / Toughest / Easiest / Tricky / At-risk** | "Targeted practice" + target accuracy and coverage | Overall score (secondary), targeted topics, mistakes cleared, next practice |
| **Mistakes** | "Mistakes cleared" + remaining | Accuracy on the mistake pool, which mistakes remain |
| **Unseen** | "Coverage" + unseen questions covered | Accuracy as a prior-knowledge baseline; not a mock |
| **Custom (bookmark/tag/difficulty)** | "Custom set" + composition | Accuracy, topics, next action |
| **Short test (<20)** | "Short test" + raw score | No margin, no trend; review and topics only |
| **Legacy full-scope attempt** | "Legacy attempt" + warning | Question review; no comparable score or trend |

For practice types, the overall percentage is deliberately not the hero
number. A Weakest-topics test is expected to score lower than a random mock;
making that the headline would punish the learner for doing the right thing.
The hero is the **target**: which topics were targeted, how many were covered,
and whether accuracy on them is moving.

### 11.4 Score and meaning

Show both denominators, because they answer different questions:

- **Score:** `attempt.score / attempt.maxScore`, where `maxScore` includes
  every graded question (including skips) and negative marking applies only to
  wrong answers. This is what an exam would report.
- **Accuracy on answered:** `correct / (correct + wrong)`, excluding skips and
  ungraded rows. This is how well the learner did on what they attempted.
- **Coverage of the attempt:** `answered / shown`; a low value is a pacing or
  confidence signal, not necessarily a knowledge signal.

Also show:

- **Correct / wrong / skipped / ungraded** counts, with ungraded defined as
  "no answer key, excluded from scoring".
- **Negative marking** applied, if the paper has it: say that it applies to
  **wrong answers only**. Skips are not penalised; they only add to
  `maxScore`.
- **Chance-corrected accuracy** for single-correct questions:
  `(p − 1/k) / (1 − 1/k)`, where `k` is the number of options. Label it
  "approximate"; multi-correct questions and negative marking need a
  per-question expected-value model and are not corrected in phase 1.
- **Sample size and margin** (section 11.5).

Example wording:

> Answered 18 of 20: 12 correct, 4 wrong, 2 skipped, 2 ungraded. Accuracy on
> answered is 12/18 = 67%. With 1/3 negative marking, the score is
> (12 − 4/3) / 18 = 10.67/18 = 59.3%. The two skips are in the denominator
> but are not penalised; the four wrong answers are. With 20 questions, the
> 95% margin is about ±22 points.

### 11.5 Comparability, confidence and trend

Every result gets a badge:

- `Mock · Random · 20 questions · comparable`
- `Mock · Random · 20 questions · fresh sample`
- `Mock · Balanced · 50 questions · comparable`
- `Practice · Weakest topics · 20 questions · not comparable`
- `Short test · 12 questions · no margin`
- `Legacy attempt · not comparable`

Rules:

- **Comparability key** = `(paperId, testType, scopeKey, requestedCount,
  samplingVersion, exclusionMode)`. Two attempts are comparable only when all
  six match.
- **Strict vs fresh.** `exclusionMode = NONE` is a strict Random/Balanced
  attempt. `RECENT_3` is a **Fresh sample**. A fresh sample is never
  comparable to a strict attempt. Two fresh samples with the same rule are
  comparable, but the margin is approximate because each excludes a different
  set; the trend legend must say so.
- **Practice** is never comparable to a mock, even at the same length and
  scope. It is selected on the dependent variable (weakness), so its score is
  biased by design.
- **Short** and **legacy** attempts never get a margin or a trend.
- **Comparable is not the same as trend-worthy.** A 20-question test is
  comparable to another 20-question test, but a single 20-question point is
  not a trend; require at least 50 shown (or a margin ≤ 10 points) before
  drawing a multi-point trend line. A 20-question result may show the **last
  comparable point** as a single reference (with its margin), but not a line;
  say why when the line is withheld.

**Margin.** For accuracy, use the Wilson interval or the normal approximation
`p ± 1.96 × sqrt(p(1−p)/n)`, then apply the finite-population correction
`sqrt((N−n)/(N−1))` when the scope size `N` is known. `n` is the **shown**
count; comparability uses the **requested** count. The margin assumes
independent questions, but passages are clusters, so label it "approximate"
and phase 2 bootstraps by resampling passages. For the score with negative
marking, a bootstrap over the attempt's per-question scores is more honest;
with ≤150 questions it is cheap. Phase 1 may use the accuracy margin and label
the score margin "approximate"; phase 2 adds the bootstrap.

**Trend.** Show a line only for comparable, trend-worthy attempts. If there
are fewer than two trend-worthy attempts, say "This is your first comparable
20-question Random test." or show the single last comparable point. If there
are two or more trend-worthy attempts, show a small line/sparkline with the
confidence band and a delta:

> Last comparable: 55% → 62% (+7 points). The margin is ±22, so this change
> could be noise. Take another 50-question mock to see whether it holds.

Never show a single trend line mixing 20-question mocks, 100-question mocks,
practice sets and legacy attempts.

**Practice trend.** Compare the same practice type's **per-topic accuracy for
the recorded target set** over the last three runs, with a small-sample caveat.
A run with empty `targetTopicIds` cannot contribute; a run that targeted a
different set is shown separately, not averaged into one line.

> Weakest-topic accuracy on the recorded topics: 42% → 48% over three runs
> (12, 15 and 20 targeted questions). This is encouraging but still a small
> sample; a random mock is the control.

### 11.6 Where the marks went: the error mix

Correct/wrong is too coarse. Classify each question using the evidence the
app already has: correctness, dwell, FSRS state, skip status and (later) a
guess flag.

| Category | Evidence rule | What it suggests |
|---|---|---|
| **Knowledge gap** | Wrong, dwell ≥ max(15 s, median), FSRS hard or no Study history | You engaged but did not know it. Review the explanation, then Study. |
| **Application/careless** | Wrong, dwell ≥ max(15 s, median), FSRS easy/high stability | You knew the material but misapplied it or misread the question. Slow down, check the stem. |
| **Rushed** | Wrong, dwell < max(5 s, 25% of median) | You answered before thinking. Practise pacing, not more content. |
| **Skipped** | No option selected, graded question | Not attempted. With no negative marking, a guess has positive expected value; with negative marking, guess only when the expected value is positive, otherwise skip. |
| **Ungraded** | No answer key | Not your fault; excluded from scoring. |
| **Possible guess** | Correct, dwell < 3 s (heuristic; a real guess flag is better) | A lucky correct is fragile. Retest it soon. |
| **Fragile correct** | Correct, FSRS hard or low stability | You got it, but memory is weak. Schedule Study soon. |
| **Solid correct** | Correct, FSRS easy/high stability, or no evidence but deliberate time | Consolidated; deprioritise. |

Show the counts and one or two examples per category, with links into the
filtered review. Do not overclaim: use "likely" and "suggests", and show the
evidence (time, FSRS difficulty, skip status).

For a small attempt (fewer than 10 timed questions), hide the relative
thresholds and show only absolute rules, labelled "small sample".

### 11.7 Topic breakdown

The existing category breakdown shows `correct/graded` per title. The report
extends it to:

| Topic | Asked | Correct | Wrong | Skipped | Ungraded | Accuracy (asked) | Sample |
|---|---|---|---|---|---|---|---|
| Polity | 12 | 5 | 3 | 2 | 2 | 42% | 12 |
| Economy | 5 | 4 | 1 | 0 | 0 | 80% | 5 |
| History | 3 | 3 | 0 | 0 | 0 | 100% | 3 — small |

Rules:

- **Topic accuracy is `correct / asked`**, so a skip or an ungraded row counts
  as a miss here. This is deliberately different from the headline accuracy on
  answered, `correct / (correct + wrong)`, and the table header says
  "Accuracy (asked)" so the two are not confused. The rows must reconcile with
  the header: correct + wrong + skipped + ungraded = asked.

- Sort weakest first, then by more asked, then by title.
- Show the scope's **accuracy (asked)** next to each topic, so "42%" has
  context; do not compare it to the exam score.
- Mark topics with fewer than 5 asked as **small sample**; do not turn a 2/2
  into a mastery claim.
- List topics in the scope that were **not sampled**. This is essential for a
  random test: a 20-question sample cannot cover 20 topics.
- Link each topic to Study filtered by that category and to a Weakest-topics
  practice test.
- For a practice test, mark the targeted topics and show target coverage:
  "Targeted 3 topics; 20 of 120 targeted questions covered." The targeted
  topics come from `targetTopicIds`, not from the sampled questions; the
  sample may not cover every targeted topic.
- **As-of-attempt is primary.** Rows use the `categoryTitle` stored on
  `question_results`, so a rename or move does not rewrite history. The
  current `categoryId` is used only for the Study link when the question still
  exists. If the question was deleted, keep the row under its stored title and
  show `N results no longer resolvable` so the totals reconcile.

### 11.8 Difficulty and memory: the gap that explains the score

This is the most diagnostic section, and the one that uses FSRS knowledge
without touching it.

Join the attempt's questions to `card_state` (read-only) and bucket them:

| | Test wrong | Test correct |
|---|---|---|
| **FSRS hard** (difficulty ≥ 5.5, valid state) | **Priority:** genuine weakness. Study, then retest. | **Fragile:** recognition worked, recall may not. Retest soon. |
| **FSRS easy** (difficulty < 5.5, valid state) | **Application/careless:** the memory is there; the test format, wording or time pressure is the problem. Inspect the explanation and dwell. | **Consolidated:** deprioritise. |

Also show:

- **No Study history:** questions with no `card_state` row, a new card, or a
  stale `contentHash`. These are the Unseen pool; do not pretend they have a
  memory estimate.
- **Due in Study:** how many of the attempt's questions are due now. The test
  did not consume them; link to Study.
- **Retrievability:** for studied cards, the predicted recall probability from
  `FsrsScheduler.retention(state, now)`. A correct answer on a low-retrievability
  card is a good sign; a wrong answer on a high-retrievability card is more
  likely careless or an application problem.

Use a quadrant/scatter labelled **"Study estimate" vs "Test accuracy"**. The
point is not to grade the learner twice; it is to choose the right remedy:
more Study, more practice, or better pacing.

### 11.9 Time analysis

Time is evidence about *how* the learner answered, not just how long they sat.

Show:

- **Total time** and, for a timed test, time used/remaining.
- **Average and median seconds per question**, compared with the target pace
  (`totalSeconds / shown`).
- **Time on correct vs wrong answers.** If wrong answers took much longer than
  correct ones, knowledge gaps are likely. If wrong answers were much faster,
  rushing/carelessness is likely. Use a 20% difference as the flag, with a
  small-sample caveat.
- **Time sinks:** the top three questions by dwell, with times and links to
  review. A question at 2× the median or above 60 s is a time sink.
- **Rushed questions:** dwell below 5 s (or 25% of median) and wrong.
- **Pacing chart:** a small bar/line of dwell per question in presented order,
  so a slow patch is visible. Mark the target pace line.

Example wording:

> Median 48 s/question against a 60 s target. Wrong answers averaged 65 s and
> correct answers 42 s, which suggests knowledge gaps rather than rushing.
> Three questions took over 90 s; review them for both content and pacing.

### 11.10 Coverage, mistakes and progress

A test is also a coverage event. Show:

- **Scope coverage:** distinct engaged questions / scope size. Count a
  question as engaged when `selectedOptionIds != ''` or `dwellSeconds > 0`;
  old huge attempts with 980 skipped rows must not inflate coverage.
- **Unseen remaining:** scope size − tested.
- **Repeat rate:** how many of this attempt's questions were tested before.
- **Mistakes added / cleared / remaining** (phase 2 query; phase 1 may show
  "wrong in this test" and "previously missed, now correct").
- **Practice target coverage:** for a focus type, the size of the targeted
  pool and how much of it this test covered.
- **Due in Study:** how many of the attempt's questions are due now; link to
  Study.
- **Leech/at-risk counts** for the attempt's questions, if the type targeted
  them.
- **`question_stats` is currently stored.** Deleting an attempt recomputes the
  affected questions; "mistakes added/cleared" must use the same
  `finishedAt DESC, id DESC` standing rule. History's "hardest" cards change
  when an attempt is deleted, which is correct.
- **Pre-v21 limitation.** Legacy full-scope attempts inflate skip counts;
  coverage counts engaged rows, and Tricky/skip evidence is weak until bounded
  attempts exist.

Example wording:

> You have now tested 180 of 1,000 questions (18%). Six of this test's 20
> questions were repeats. Seven mistakes were added, three were cleared, and
> four of these questions are due in Study now.

### 11.11 What to do next: the recommendation engine

The report ends with three to five actions, ordered by expected value. Each
action has a title, one line of evidence and a button. The engine is a pure
function of the report, so it is testable and cannot invent evidence.

**Mock rules (priority order):**

1. **Wrong answers ≥ 3** → "Review the N wrong questions", starting with the
   slowest. Evidence: count and slowest time.
2. **A topic has ≥ 5 asked and is ≥ 10 points below the scope average** →
   "Study [topic]". Evidence: `5/12 (42%) vs 60% (scope accuracy asked)`.
3. **Skipped ≥ 3** → if the paper has **no negative marking**, "Attempt every
   question next time"; if it has negative marking, "Guess only when you can
   eliminate enough options." Evidence: skipped count and the marking rule.
4. **Length 20 and margin ≥ 15** → "Take a longer mock for a tighter
   estimate." Use the largest allowed length (`min(50, maxAllowed)`); if
   `maxAllowed < 50`, say "up to N questions" and do not claim ±14. Evidence:
   the current margin vs the target length's margin.
5. **Coverage < 50%** → "Test unseen questions to cover the bank." Evidence:
   `180/1,000 tested`.
6. **Time sinks ≥ 3** → "Review your time sinks and practise pacing."
   Evidence: the three slowest questions.
7. **All of the above are small** → "Keep going; take another comparable mock
   in a week."

**Practice rules:**

1. **Targeted topic still weak** → "Repeat this focus test in 2–3 days."
   Evidence: target accuracy and sample size.
2. **Mistakes remain** → "Practise the remaining N mistakes."
3. **Target accuracy improved but no random control** → "Take a random mock
   to check transfer." Evidence: practice gains can be regression to the mean.
4. **Target pool covered < 50%** → "Continue this focus set; N targeted
   questions remain."
5. **At-risk type** → "These questions are still due in Study; review them
   there." Evidence: due count; the test did not consume them.

**Custom rules:**

1. Wrong/slow questions → review them.
2. Bookmarked set → "Remove bookmarks you have mastered" when accuracy is high
   across repeated attempts.
3. Tag/difficulty set → "Take a random mock to check whether the set
   generalises."

Rules:

- Cap at three to five actions. More than that is a data dump, not guidance.
- Every action shows its evidence number and sample size.
- Every action links to an existing route: Study (with category ids), Test
  (with type, count, scope), or Results review (with a filter).
- Never recommend a practice score as proof of improvement; recommend a
  random control when the claim is about mastery.
- If the attempt is short or legacy, suppress the statistical actions and
  keep only review/topic actions.

### 11.12 Question review with per-question evidence

Keep the paged review, but extend the filters and the card:

**Filters:** All, Wrong, Skipped, Ungraded, Slow, Rushed, Possible guess,
Fragile correct, Saved, Targeted (for practice tests).

**Sort:** presented order (default), slowest, hardest (FSRS), most missed in
history, lowest lifetime accuracy.

**Each card shows:**

- The question, the learner's selection and the correct answer.
- The explanation and explanation image (existing).
- Time spent and the attempt median, e.g. `1:45 · median 0:48`.
- Category and, when valid, `Study difficulty: 7.2/10` and
  `predicted recall: 82%`.
- Lifetime accuracy on this question, with sample size and **source**:
  `3/7 (43%) in comparable mocks` (reads `rep*`) or `3/7 (43%) in all test
  attempts` (reads all-attempt counters). If the source has no evidence
  (`repAnswered == 0`, or `answered == 0`), say "no comparable mocks yet" or
  "no test history yet" instead of showing 0/0. A practice report must not show
  the mock number, and vice versa.
- A "why this matters" tag: knowledge gap, application/careless, rushed,
  skipped, possible guess, fragile correct, solid correct.
- For a practice test, the targeting reason: "Weakest topic", "Mistake",
  "Tricky", "At risk", "Unseen".
- Actions: Bookmark, "Study this" (question/category), "Practice this"
  (a 20-question focus test containing it, later).

For a legacy huge attempt, keep the paged list and hide per-question FSRS
badges where the question no longer exists. The report header says the
attempt is legacy and not comparable.

### 11.13 Legacy, short and full-scope attempts

- **Legacy:** `testType = NULL` or `samplingVersion < 2`, or a pre-redesign
  attempt with a huge `totalQuestions` and many skips. Show a banner: "This
  was a full-scope attempt with many skipped questions. Its percentage is not
  comparable to a bounded test; use the question review." Hide margin/trend.
  Count only engaged questions for coverage.
- A legacy **drill snapshot** resumes as Short/Legacy, never as a 10-question
  Random test.
- **Pre-v21 limitation:** legacy full-scope attempts inflate `skipCount`;
  `skip(q)` and Tricky are weak until bounded attempts exist. No backfill or
  exclusion is applied.
- **Short (<20):** label "Short test"; no margin, no trend; still show topics,
  time and review. It can still generate Study/review actions.
- **Full scope (≤150):** label "Full scope"; no sampling error, but still show
  item-quality and fatigue caveats. Do not call it a random sample.

### 11.14 Data and computation

Add a pure `TestReport` model and a `TestReportBuilder` so the page is not a
pile of ad-hoc queries in the ViewModel:

```
data class TestReport(
    val attempt: Attempt,
    val plan: TestPlan?,          // null for legacy/short attempts
    val score: ScoreSummary,
    val comparability: Comparability,
    val errorMix: ErrorMix,
    val topics: List<TopicResult>,
    val memory: MemoryQuadrants?,
    val timing: TimingSummary,
    val coverage: CoverageSummary,
    val mistakes: MistakeSummary,
    val actions: List<RecommendedAction>
)
```

`plan` is nullable: legacy and short attempts have no original `TestPlan`. The
report derives a display plan from the attempt (`requestedCount`,
`totalQuestions`, type) and labels it.

`RecommendedAction` carries `title`, `evidence`, `priority`, `kind`
(Study/Test/Review) and the route arguments. The builder takes the attempt,
results, category rows, card states, lifetime stats and previous attempts, and
is JVM-testable with synthetic data.

**Queries to add** (phase 1/2):

- `AttemptDao.getComparableAttempts(paperId, testType, scopeKey,
  requestedCount, samplingVersion, exclusionMode, excludeId, limit)` — same
  key, newest by `finishedAt DESC, id DESC`; excludes legacy
  (`testType IS NULL`, `samplingVersion < 2`).
- `AttemptDao.getResultSummary(attemptId)` — counts, score, answered, dwell
  sum/count, min/max; median can be computed from the bounded rows.
- `AttemptDao.getResultCategoryBreakdown(attemptId)` — extend to
  asked/correct/wrong/skipped, using the **stored** `categoryTitle`; join
  `questions` only to resolve the current `categoryId` for a Study link.
- `AttemptDao.getResultQuestionMeta(attemptId)` — for bounded attempts:
  question id, stored category title, current category id when it still
  exists, authored difficulty, content hash, dwell, correctness,
  selected/correct ids.
- `AttemptDao.getCoverageForPaper(paperId, scopeKey)` — distinct engaged
  questions, with a scope variant.
- `AttemptDao.getPriorStandings(attemptId)` — the latest graded, answered
  result before this attempt by `finishedAt DESC, id DESC`, for "mistakes
  added/cleared".
- `QuestionStatsDao.getRepresentativeForQuestions(ids)` — `rep*` lifetime
  accuracy for mock context.
- `QuestionStatsDao.getAllForQuestions(ids)` — all-attempt lifetime accuracy
  for practice context.
- `CardStateDao.getForQuestions(ids)` already exists for FSRS state.
- `CardStateDao.countDueForQuestions(ids, now)` — "due in Study" count.

**Performance.** A bounded attempt has at most 150 results, so the report can
load them all once and compute medians, quadrants and the error mix in Kotlin.
Legacy attempts keep the paged review and use SQL aggregates; the report hides
the expensive sections. Never load question content for the whole paper, only
for the attempt's questions.

### 11.15 Implementation phases

**Phase 1 (with the unified Test):**

- Header with type, scope, requested/shown, score, accuracy, counts, time.
- Comparability badge and approximate margin.
- Topic breakdown with sample-size badges and not-sampled topics.
- Basic error mix: wrong/slow (knowledge gap), wrong/fast (rushed), skipped,
  ungraded, possible guess.
- Time summary: total, average/median, slowest questions.
- Three actions: review wrong, study weakest topic, take a longer mock.
- "This test did not change your Study schedule" note.
- Extended question filters (wrong, skipped, slow, saved).
- Legacy/short/full-scope labels, including a legacy drill snapshot.
- Fresh sample badge on the result, History row and Statistics legend.
- Recorded `targetTopicIds` for Weakest topics (and later Mixed).

**Phase 2:**

- FSRS memory-vs-performance quadrants and per-question Study badges.
- Coverage, repeats, mistakes added/cleared, due-in-Study count.
- Comparable trend with confidence bands; practice per-topic trend on a fixed
  recorded target set.
- Practice-specific headline and recommendations.
- Bootstrap confidence interval for the score with negative marking,
  resampling passages.
- Per-question lifetime accuracy (rep* for mock, all-attempts for practice)
  and "why this matters" tags.

**Phase 3:**

- Item analysis and reliability (split-half/Spearman–Brown, SEM).
- Export/share the report (PDF/CSV).
- Adaptive next-test recommendation (which type/length to take next).
- Fixed-panel progress for a stable question set.

### 11.16 Tests and acceptance

**Pure JVM tests**

- `TestReportBuilderTest`:
  - score/accuracy with skips, ungraded and negative marking;
  - margin and finite-population correction;
  - comparable vs practice vs short vs legacy;
  - error mix thresholds with a synthetic dwell distribution;
  - topic sorting and small-sample flags;
  - memory quadrants with valid, stale and missing `card_state`;
  - recommendation priority and cap (never more than five);
  - practice actions never claim mock comparability.
- `RecommendationEngineTest`: each rule fires on its evidence and not
  otherwise; no rule invents a number.

**Database tests (Robolectric, CI x86_64)**

- Comparable-attempt query matches the full key, including exclusion mode.
- Coverage counts engaged questions, not old skipped rows.
- Prior-standings query returns the correct before/after for mistakes.
- Due-in-Study count is read-only and leaves `card_state` unchanged.
- Report queries return the same counts as the attempt header.

**UI tests**

- A 20-question Random result shows the margin, comparability badge and
  "first comparable attempt" when there is no prior.
- A Weakest-topics result shows the practice headline and does not show a
  mock trend.
- A short test shows no margin/trend.
- A legacy attempt shows the warning and hides the trend.
- Every action button navigates to the right Study/Test/Review route.
- The "Study schedule unchanged" note is present.
- Question filters and per-question evidence render.

**Acceptance criteria**

- [ ] The result page says what kind of test it was and whether it is
      comparable.
- [ ] It shows score and accuracy separately, with correct/wrong/skipped/
      ungraded and the negative-marking note.
- [ ] It shows the sample size and an approximate margin for representative
      tests.
- [ ] It shows the weakest topics with sample sizes and the topics that were
      not sampled.
- [ ] It explains where the marks went with evidence (time, FSRS, skip,
      guess), using "likely/suggests" language.
- [ ] It shows time analysis, including slowest questions and wrong-vs-correct
      pace.
- [ ] It shows coverage, repeats, mistakes and due-in-Study where available.
- [ ] It gives three to five evidence-backed next actions with working links.
- [ ] Practice results never show a mock trend or claim mock comparability.
- [ ] Legacy and short attempts are labelled and do not get a margin/trend.
- [ ] The page states that the test did not change the Study schedule.

## 12. Data model and migration

### 12.1 `attempts`

Add:

| Column | Type | Default | Purpose |
|---|---|---|---|
| `testType` | TEXT, nullable | `NULL` | Which selector produced the session. `NULL` = legacy. |
| `requestedCount` | INTEGER NOT NULL | 0 | What the learner asked for (20–150). 0 = legacy. |
| `scopeCategoryIds` | TEXT NOT NULL | `''` | Ticked categories, comma-joined, for display; empty = whole paper. |
| `scopeKey` | TEXT NOT NULL | `''` | Canonical, subtree-expanded scope key used for comparability. |
| `samplingVersion` | INTEGER NOT NULL | 1 | Reproducibility. New attempts write **2**. |
| `seed` | INTEGER NOT NULL | 0 | Random/balanced seed. |
| `exclusionMode` | TEXT NOT NULL | `NONE` | `NONE` = strict sample; `RECENT_3` = fresh sample. |
| `targetTopicIds` | TEXT NOT NULL | `''` | Sorted, deduped, comma-joined Weakest/Mixed targets. |
| `compositionJson` | TEXT NOT NULL | `''` | Focus fills, e.g. `{"mistakes":12,"toughest":8}`. |

`totalQuestions` already records the shown count. `Attempt` gains the same
fields with defaults so old rows and old code keep working.

**Legacy defaults are deliberate.** A pre-v21 row gets `testType = NULL`,
`requestedCount = 0` and `samplingVersion = 1`. It is therefore never
comparable and never contributes to `rep*` counters, even though
`exclusionMode` defaults to `NONE`. Do not infer `RANDOM` for a `NULL` type;
`NULL` means "unknown design", not "strict random".

**Canonical scope key.** `scopeKey` is built from the ticked scope expanded
once with `CategorySelection.orderedIds`: each ticked node expands to itself
and its whole subtree (the node plus its descendants), duplicates are removed,
and the ids are sorted, joined with a separator and hashed. The UI normalises
a fully ticked subtree to the maximal parent tick, so a parent tick and its
fully ticked children expand to the same set. The whole paper is a distinct
constant (e.g. `paper:<paperId>`), never the empty string. `scopeCategoryIds`
keeps the user's ticked ids for display; it is not the comparability key. Two
different tick sets that expand to the same set must produce the same
`scopeKey`, and reordering ticks must not change it.

**`targetTopicIds`.** Populated only for Weakest topics (and later Mixed): the
ids the weakest-topic ranking selected, sorted, deduped and comma-joined. A
target may be a non-leaf category with its own questions; if the product later
wants leaf-only targets, the ranking rule must say so explicitly. It is
**outside** the comparability key, so a Weakest-topics run remains a practice
run whatever it targeted; the per-topic trend compares the recorded set
separately (section 11.5). Empty for every other type and for a run with no
target.

Migration `v20 → v21`: `ALTER TABLE attempts ADD COLUMN ...` with the defaults
above; `testType` is added without `NOT NULL`. Update `AttemptDto`, `Exporter`,
`Importer`, `StreamingImportSession` and the backup/restore round-trip tests.
Unknown fields in an old backup are fine because the DTO fields have defaults.

Add the comparable-key index from section 12.3. Its leading `paperId` filters a
paper's attempts; the per-paper result set is small and is sorted in memory, so
there is no separate `attempts(paperId, finishedAt)` index in phase 1.

### 12.2 `TestSnapshot`

Add:

| Field | Type | Default | Purpose |
|---|---|---|---|
| `testType` | String?, nullable | `null` | Resume only the same type; `null` = legacy snapshot. |
| `requestedCount` | Int | 0 | Resume only the same length; 0 = legacy. |
| `selectedQuestionIds` | List<String> | empty | Frozen metric-based selection, in presented order. |
| `samplingVersion` | Int | 1 | Compatibility; new snapshots write 2. |
| `exclusionMode` | String | `NONE` | Resume only the same exclusion rule. |
| `targetTopicIds` | List<String> | empty | Recorded practice targets. |

Keep `isDrill`, `drillRequested`, `drillSeed` for reading old snapshots; new
code writes `testType` and `requestedCount`. A snapshot with `testType = null`
or `samplingVersion < 2` is a **legacy snapshot**: it resumes as a labelled
Short/Legacy session or offers "Start a new test", and is never silently
reinterpreted as a 10-question Random test.

`matches()` includes the type, count, exclusion mode, target set and the
expanded `scopeKey` (not the raw tick order). A Weakest-topics snapshot must
not be offered to a Random test of the same scope, and vice versa. The selected
ids are at most ~150 strings, so the snapshot stays small.

### 12.3 `question_stats` and indexes

**Current columns.** `question_stats` keys on `questionId` and stores
`paperId`, `paperTitle`, `categoryTitle`, `text`, `totalAttempts`,
`gradedCount`, `correctCount`, `wrongCount`, `skipCount`, `ungradedCount`,
`lastResultIsCorrect` and `lastFinishedAt`. There is no index on `paperId`, so
the weakest-category and per-paper queries scan the whole aggregate table.

**Representative counters.** Add three columns, all `INTEGER NOT NULL DEFAULT 0`:

| Column | Meaning |
|---|---|
| `repCorrect` | Correct answers in representative attempts. |
| `repWrong` | Wrong answers in representative attempts. |
| `repSkip` | Skips in representative attempts. |

`repGraded` is **not stored**: compute `repCorrect + repWrong + repSkip` at
read, so it cannot drift from the three counters.

An attempt is representative only when `testType IN ('RANDOM','BALANCED')` and
`samplingVersion >= 2`. Practice, legacy and `NULL`-type attempts never touch
these columns. Because they start at 0 at migration and there is no backfill,
they describe only post-migration representative attempts; that is the
intended scope, not a bug.

**Write rule.** Saving an attempt increments the all-attempt counters for every
result and, only for a representative attempt, the `rep*` counters for the same
result. It also updates `lastResultIsCorrect` / `lastFinishedAt` when the new
result is later by `finishedAt DESC, id DESC`. This happens in the same
transaction as the result insert.

**Write-rule guard is deferred.** The shipped write path still overwrites
`lastResultIsCorrect` / `lastFinishedAt` unconditionally; the
`finishedAt DESC, id DESC` guard lands with the attempt-metadata slice, which
must also decide the tie-break input — the result id is not available before
the insert, so it needs the inserted id or a stored last-result id. Do not add
a `lastResultId` column in the schema slice.

**Delete rule: `question_stats` means "currently stored".** The current
`QuestionStatsEntity` comment says deleting an attempt intentionally leaves the
row untouched; that comment and the behavior change here. Deleting an
attempt recomputes the affected questions from the surviving
`question_results`, in the same transaction:

1. Recompute `totalAttempts`, `gradedCount`, `correctCount`, `wrongCount`,
   `skipCount` and `ungradedCount`.
2. Recompute `repCorrect`, `repWrong` and `repSkip` by joining the surviving
   results to `attempts` and keeping only representative attempts. `repGraded`
   is derived at read, not recomputed or stored.
3. Recompute `lastResultIsCorrect` and `lastFinishedAt` from the latest
   graded, answered result by `finishedAt DESC, id DESC`. If there is no
   graded, answered result but there are skip-only results, set
   `lastResultIsCorrect = false` (a skip is not a correct result) and
   `lastFinishedAt` to the latest surviving result's attempt `finishedAt`.
   Never carry a value forward from the deleted attempt.
4. Delete the row **only when `totalAttempts == 0`**. A row with
   `totalAttempts > 0` but no answered result (`correctCount = wrongCount = 0`)
   is a skip-only row and must survive: it still records exposure.
   `gradedCount` includes skips (section 9.2), so it is not zero here. A row
   whose last result is deleted but which still has other results is
   recomputed, never dropped.

Every delete path is enumerated in section 3.1 (History delete, paper delete,
category delete, question delete, backup restore). The paper, category and
question paths sweep rows because the questions themselves are gone; the
History path recomputes. Backup restore empties the table and rebuilds it from
the imported results through the same write rule.

**Indexes.**

| Index | Purpose |
|---|---|
| `question_stats(paperId)` | Per-paper aggregates. |
| partial `question_results(questionId, id) WHERE selectedOptionIds != ''` | Latest-standing and per-question history over graded, answered rows. |
| `attempts(paperId, testType, requestedCount, scopeKey, samplingVersion, exclusionMode, finishedAt)` | Comparable-attempt lookup. |
| `card_state(paperId, leech, dueAt)` | Leech/at-risk queries. |

`question_results` already has `(attemptId, isCorrect)`; `card_state` already
has `(paperId, dueAt)` and `(paperId, cardState, dueAt)`; `review_log` already
has `(questionId, reviewedAt)` and `(paperId, reviewedAt)`. `lastFinishedAt`
stays a stored column, but no planned query orders by it yet; add an index only
when one does.

**The partial index needs two creation paths.** Room's `@Index` cannot express
a `WHERE` clause, so the index is created by raw SQL. A v20 → v21 migration
that only runs `ALTER TABLE` would leave a fresh install without the index, and
an `onCreate` callback alone would leave upgraded installs without it. Do
both:

1. The v20 → v21 migration executes
   `CREATE INDEX IF NOT EXISTS index_question_results_question_id_graded ON question_results(questionId, id) WHERE selectedOptionIds != ''`.
2. `AppDatabase` registers a `RoomDatabase.Callback` whose `onCreate` executes
   the same statement for a fresh database.

Test both paths: migrate a v20 database and create a fresh v21 database, then
assert the index exists in each (`PRAGMA index_list('question_results')`).
Because Room validates only the indices declared on entities, an extra raw
index does not break schema validation.

**As-of-attempt vs current category.** Report rows use the `categoryTitle`
stored on `question_results`, so a rename or move does not rewrite history.
Ranking may join `question_stats.questionId` to `questions.id` to use the
current `categoryId`; a deleted question drops out of the join, which is
correct because it cannot be tested. A later `categoryId` column on
`question_stats` would make ranking cheaper but is not required for phase 1.

### 12.4 Tags

`questions.tags` is a comma-joined string; `tagsFolded` is for search. Exact
tag filtering with `LIKE '%tag%'` scans and can match substrings. Phase 3
should add a `question_tags(questionId, tag)` table (with an index on `tag`),
maintained on import/edit, or store tags with delimiters and use exact
matching. Until then, the Tag type is either deferred or limited to small
banks.

## 13. Implementation plan

### Phase 1 — unified bounded Test (the core fix)

1. **Domain**
   - Add `TestType` (`RANDOM`, `BALANCED`, `WEAKEST_TOPICS`,
     `TOUGHEST_QUESTIONS`, `EASIEST_QUESTIONS`, `MISTAKES`, `TRICKY`,
     `AT_RISK`, `UNSEEN`, `BOOKMARKED`, `TAG`, `AUTHORED_DIFFICULTY`,
     `SLOWEST`, `MIXED`).
   - Add `TestPlan` (`paperId`, `categoryIds`, `type`, `count`, `minutes`,
     `seed`, `samplingVersion`, `scopeKey`, `exclusionMode`,
     `targetTopicIds`) with `available`, `maxAllowed`, `isValid`,
     `resolve(available)` and `suggestedMinutes`. `maxAllowed` is the largest
     allowed length that fits the pool, clamped to 150 and never below the
     short-test size.
   - Add a single `ScopeKey` helper: expand the ticked scope with
     `CategorySelection.orderedIds`, dedupe, sort, join and hash; the whole
     paper is a distinct constant. All writers and readers must use it, so a
     resumed plan and a saved attempt agree.
   - Add `ExamTiming.suggestedMinutes(count, secondsPerQuestion)` and the
     seconds-per-question default/clamp.
   - Add `SessionOrder.sampledBounded(target, max, seed)`: shuffle the blocks
     first, then an exact-fit DP up to `target`, then a single overshoot within
     `max(1, ceil(0.25 × target))`, never above 150, with a deterministic
     tie-break. Keep `sampled` for legacy snapshots or fold it in with tests.
   - Add the pure report model and builders: `TestReport`, `TestReportBuilder`
     and `RecommendationEngine` (section 11.14), so the result page has no
     ad-hoc scoring logic.
2. **Data**
   - Migration v20 → v21 for the `attempts` columns and the three `rep*`
     columns on `question_stats`; new attempts write `samplingVersion = 2`.
   - Create the partial `question_results` index in **both** the v20 → v21
     migration and a `RoomDatabase.Callback.onCreate` for fresh installs.
   - Update `Attempt`, `AttemptEntity`, `AttemptDto`, `Exporter`, `Importer`,
     `StreamingImportSession`.
   - Add the new `TestSnapshot` fields with defaults.
   - Add the indexes listed in sections 9.10 and 12.3.
   - Fix `getLatestStandings` and `countMistakesByPaper` to order by
     `finishedAt DESC, id DESC`; this is a query rewrite, not an index.
   - Implement the `question_stats` recompute on delete (all-attempt and
     `rep*` counters, last-standing fields, delete only at
     `totalAttempts == 0`).
3. **Repository/VM**
   - `TestViewModel` accepts a `TestPlan` (route: `type`, `count`, `minutes`,
     `exclusion`; keep old `drillCount`/`drillMinutes` parsing for old deep
     links). A missing `type` is legacy, not Random.
   - Expand the scope once with `CategorySelection.orderedIds` before any
     selector or quota calculation; pass the full expanded list (parents
     included) to `getOrderForCategories`, whose query is
     `categoryId IN (...)` so each question matches once.
   - Always use `sampledBounded` for the session order, including Mistakes.
   - `countUngraded` always uses the bounded id list.
   - Save the attempt metadata (`testType`, `requestedCount`,
     `scopeCategoryIds`, `scopeKey`, `samplingVersion = 2`, `seed`,
     `exclusionMode`, `targetTopicIds`, `compositionJson`).
   - Resume builds the order from `selectedQuestionIds`; never re-ranks. A
     legacy snapshot (`testType = null` or `samplingVersion < 2`) resumes as a
     labelled Short/Legacy session or offers a new test.
   - `HistoryStore.deleteAttempt` runs the section 12.3 recompute in the same
     transaction; the other delete paths keep their sweeps (section 3.1).
   - `ResultsViewModel` builds a `TestReport` from the attempt, bounded result
     rows, card states and prior standings; the screen renders the model
     instead of recomputing in Compose. `TestReport.plan` is nullable and a
     legacy/short attempt gets a display plan derived from the attempt.
   - Add the boundary regression test: `card_state` and `review_log` counts
     are unchanged after a save.
4. **UI**
   - Replace `TestDurationDialog` with `TestSetupSheet`.
   - Remove the separate drill icon/dialog and the `quick_drill`/
     `start_drill` strings.
   - The Mistakes shortcut opens the sheet with `MISTAKES` preselected.
   - Length chips and stepper, type cards, scope summary, time, preview,
     validation, short-test label, and the "Avoid recently tested questions"
     setting.
   - Test session header: "Random · 20 questions" or "Practice · Weakest
     topics · 20 questions"; keep the "N asked, M shown" passage note.
   - Results and History: show the type badge, the composition and the Fresh
     sample badge when `exclusionMode = RECENT_3`.
   - **Results report (phase 1, section 11):** header (type, scope,
     requested/shown, score, accuracy, counts, time), comparability badge,
     approximate margin, topic breakdown with small-sample, as-of-attempt
     titles and not-sampled notes, basic error mix, time summary and time
     sinks, three evidence-backed actions, "Study schedule unchanged" note,
     extended question filters, and legacy/short/full-scope labels.
   - Replace the current `ResultsScreen` header and category tally with the
     report sections; keep the paged question review underneath.
5. **Docs**
   - Fix the README's drill claims; document the unified Test, the length
     rules and the type families.

### Phase 2 — study-informed focus types

1. Add `QuestionSignals` / `TestRanking` (pure, JVM-testable): Wilson lower
   bound, composite toughness/ease, category weakness, neutral priors, stale
   hash handling.
2. Add DAO queries and indexes:
   - category aggregates for the scope;
   - top-N toughest/easiest questions in the scope;
   - latest mistake standings (reuse `AttemptDao.getLatestStandings`);
   - leech/lapse/guess aggregates;
   - due/at-risk and unseen counts and samples.
3. Implement the selectors:
   - Weakest topics (top-K + allocation);
   - Toughest questions (top-N + category cap);
   - Easiest questions (top-2N sample);
   - Mistakes (existing rule, bounded);
   - Tricky, At-risk, Unseen.
4. Implement the fill ladder and `compositionJson`.
5. UI: type cards with counts and "why these questions" text; practice badge.
6. **Results report (phase 2, section 11):** FSRS memory-vs-performance
   quadrants and per-question Study badges; coverage, repeats, mistakes
   added/cleared and due-in-Study; comparable trend with confidence bands and
   the practice target trend on a fixed recorded target set; practice-specific
   headline and recommendations; bootstrap score interval resampling
   passages; per-question lifetime accuracy (`rep*` for mock, all-attempts for
   practice) and "why this matters" tags.
7. Statistics: separate mock and practice families, and separate strict from
   Fresh sample attempts; add the practice progress views (weak-topic
   accuracy, mistakes cleared, leeches, at-risk, unseen coverage).

### Phase 3 — custom and advanced

1. Bookmarked, Tag, Authored difficulty, Slowest.
2. `question_tags` table (or delimited exact matching).
3. Mixed practice composition.
4. Exam blueprint (per-paper sections, counts, optional weights/time).
5. Reliability, item analysis, guessing correction, staged/adaptive tests.

## 14. Test plan

**Domain (pure JVM)**

- `TestPlanTest`
  - allowed lengths: 20, 30, …, 150; 10 and 160 are rejected;
  - presets are exactly 20/50/80/100/120/150;
  - default 20;
  - `available = 0`, `available < 20`, `available = 25`, `available = 137`;
  - `count > available` clamps to `maxAllowed`, and `maxAllowed` never exceeds
    150; a 200-question pool with a 150 request shows 150;
  - time suggestion: 20 → 20 min, 50 → 50 min, 150 → 150 min;
  - override and untimed;
  - short-test rule;
  - type/scope validation, including `exclusionMode` and
    `targetTopicIds`;
  - a legacy plan (`testType = null`, `requestedCount = 0`) is not resolved as
    Random.
- `ScopeKeyTest`
  - the same scope in a different tick order produces one key;
  - a parent tick and its child ticks produce the same key;
  - duplicate ticks are removed;
  - a whole paper and an empty category list differ;
  - a key is stable across process restarts (no map iteration order).
- `SessionOrderTest`
  - `sampledBounded` never exceeds 150;
  - the DP finds an exact fit that a greedy pass would miss: blocks 6, 5, 5
    reach 10 as 5 + 5;
  - a 19 + 130 pool does not become 149; it stops at 19 and is labelled short;
  - a shown count below 20 from a scope of at least 20 is classified as a
    Short test (non-comparable, no margin, no trend);
  - an all-30-passage scope with a 20-question target selects nothing and
    refuses with a specific message; it never returns an empty session, and a
    30-question target on the same scope selects 30 exactly;
  - overshoot stays within `max(1, ceil(0.25 × target))`;
  - passages stay whole;
  - a single block > 150 is rejected;
  - shuffle first, then DP: two seeds give different samples, the same seed
    gives the same sample;
  - the tie-break prefers the earlier block in shuffled order;
  - `count == size` and `count > size`.
- `QuestionSignalsTest`
  - Wilson lower bound values;
  - neutral priors for missing evidence;
  - stale hash treated as new;
  - toughness/ease ordering on synthetic data;
  - category weakness ranking;
  - representative rankings read `rep*`, personal rankings read all-attempt
    counters.
- `TestSelectorTest`
  - a ticked parent expands to itself and its whole subtree, and the parent's
    own questions enter the quota;
  - a parent plus a child is deduped, so no question is counted twice;
  - largest-remainder allocation with caps and redistribution;
  - min 1 per category when `n >= categories`;
  - the 40% cap is `max(1, ceil(0.40 × n))`; when `cap × K < n` it relaxes to
    `ceil(n / K)` and redistributes (two categories at `n = 20` become 10 + 10);
  - fill ladder composition;
  - Mistakes excludes skips and ungraded rows and orders by
    `finishedAt DESC, id DESC`;
  - Weakest topics falls back to authored difficulty when no evidence, and
    records `targetTopicIds` even when the sample misses one target;
  - Easiest excludes leeches/at-risk when alternatives exist.
- `TestReportBuilderTest`
  - score vs accuracy with skips, ungraded rows and negative marking:
    12 correct, 4 wrong, 2 skipped, 2 ungraded at 1/3 marking gives
    `(12 − 4/3) / 18 = 59.3%`, with skips in the denominator but not
    penalised;
  - margin with the finite-population correction, using the **shown** count,
    not the requested count;
  - comparable, practice, short and legacy classification;
  - strict vs fresh sample: fresh-vs-fresh is comparable, fresh-vs-strict is
    not, and the fresh margin is labelled approximate;
  - a `null` `TestPlan` builds a display plan for a legacy/short attempt;
  - error-mix thresholds on a synthetic dwell distribution;
  - topic sorting, small-sample flags, as-of-attempt titles and the
    unresolved-question count;
  - memory quadrants with valid, stale and missing `card_state`;
  - coverage counts engaged questions only.
- `RecommendationEngineTest`
  - each rule fires on its evidence and not otherwise;
  - at most five actions, ordered by priority;
  - practice actions never claim mock comparability;
  - short and legacy attempts suppress statistical actions;
  - skip advice changes with the marking scheme (no negative marking vs
    negative marking);
  - the longer-mock action clamps to `maxAllowed`; with `maxAllowed < 50` it
    says "up to N questions" and does not claim ±14.

**Database (Robolectric, CI x86_64 authoritative)**

- New DAO queries return the expected counts and order.
- Indexes are used (query plan assertions where practical).
- Migration v20 → v21 preserves old attempts with nullable `testType` and
  `samplingVersion = 1`; new attempts write `samplingVersion = 2`.
- The partial `question_results` index exists after **both** a v20 → v21
  migration and a fresh create (the `RoomDatabase.Callback.onCreate` path).
- Backup/export/import round-trips the new attempt fields.
- Saving an attempt leaves `card_state` and `review_log` counts unchanged.
- Deleting one attempt recomputes the affected `question_stats` rows,
  including the `rep*` counters; a skip-only row (`totalAttempts > 0`,
  `correctCount = wrongCount = 0`) survives, and a row whose last result is
  deleted is removed.
- A practice attempt increments the all-attempt counters but not `rep*`; a
  representative Random/Balanced attempt with `samplingVersion = 2` increments
  both. A legacy attempt (`testType = NULL` or `samplingVersion = 1`)
  increments only the all-attempt counters.
- Deleting a representative attempt subtracts from `rep*` by recomputation,
  not by blind decrement; deleting a practice attempt leaves `rep*` unchanged.
- Paper, category, question and restore paths leave no orphaned
  `question_stats` rows.
- `getLatestStandings` and `countMistakesByPaper` pick the result by
  `finishedAt DESC, id DESC`, including after a merge import.
- Comparable-attempt query matches the full key, including exclusion mode.
- A 1,000-question paper with a 20-question plan saves
  `requestedCount = 20` and `totalQuestions = shown` (20, or the passage
  overshoot).
- Report queries match the attempt header: score, counts, categories, dwell
  median and engaged coverage.
- Prior-standings query returns the correct before/after for mistakes.
- Due-in-Study count is read-only and leaves `card_state` unchanged.

**ViewModel/UI**

- Start a 20-question Random test from a 1,000-question paper in two taps.
- Submit stores 20 results, not 1,000.
- A 150-question plan never exceeds 150 shown.
- A passage overshoot shows "N asked, M shown".
- Resume replays the same selected ids for a focus type after metrics change.
- A Random snapshot is not offered to a Weakest-topics test of the same scope.
- The Mistakes shortcut opens the sheet with Mistakes selected.
- The drill icon/dialog is gone; no `drillCount` UI remains.
- Presets disable correctly for small scopes and type pools.
- Short-test label appears for a scope < 20.
- **Results page:** a 20-question Random result shows the comparability badge,
  margin, score vs accuracy, error mix, topics, time and three actions.
- A Weakest-topics result shows the practice headline and no mock trend.
- A short test shows no margin or trend; a legacy attempt shows the warning.
- Every action button navigates to the correct Study/Test/Review route.
- The "Study schedule unchanged" note is present.
- Question filters (wrong, skipped, slow, saved) and per-question evidence
  render.
- A Fresh sample badge appears in History and in the Statistics trend legend.
- The "Avoid recently tested questions" setting is on by default; turning it
  off labels the attempt `comparable`, turning it on labels it `fresh sample`.
- A legacy drill snapshot opens as Short/Legacy, not as a 10-question Random.
- A per-question lifetime accuracy in a mock says "comparable mocks"; in a
  practice report it says "all test attempts".
- A Weakest-topics result shows the recorded target topics even when one
  target received no sampled question.
- Deleting an attempt from History refreshes the affected "hardest" card and
  the per-question accuracy on the next open.

**Statistics**

- Mock and practice attempts are in separate groups.
- Strict and Fresh sample attempts are in separate groups; a fresh sample is
  never plotted against a strict Random attempt.
- The trend line never mixes a 20-question practice set with a 100-question
  mock.
- The practice per-topic trend compares only runs with the same recorded
  `targetTopicIds`; an empty-target run cannot contribute.
- Sample size and confidence band are shown; a fresh sample's band is labelled
  approximate.
- Coverage counts engaged questions, not old skipped rows.
- "Hardest" and per-question accuracy reflect stored attempts only; deleting
  an attempt changes them.

## 15. Acceptance criteria

- [ ] There is one Test entry point; Quick drill no longer exists as a
      separate icon, dialog or route.
- [ ] Test always asks for a length; allowed lengths are 20–150 in steps of
      10; presets are 20/50/80/100/120/150; the default is 20.
- [ ] A scope smaller than 20 gets a labelled **Short test · N questions**, not
      a disabled Test button; a sampler result below 20 from a larger scope is
      also labelled **Short test** and gets no margin or trend.
- [ ] The sampler never returns an empty session; when no passage fits the
      requested length and overshoot limit, it refuses with a specific message
      and suggests a longer length.
- [ ] A 1,000-question paper can start a 20-question test in two taps.
- [ ] Submitting that test stores `requestedCount = 20` and
      `totalQuestions = shown` (20, or the passage overshoot), not 1,000.
- [ ] A test never contains more than 150 questions.
- [ ] The sampler prefers an exact fit; a 19 + 130 pool does not become 149,
      and a 6 + 5 + 5 pool reaches 10 as 5 + 5.
- [ ] Unanswered questions within the sample are skipped; questions outside
      the sample are not in the attempt at all.
- [ ] Scope and length are shown separately on the start sheet and the result.
- [ ] Ticking a parent topic includes its subtree, and ticking a parent plus
      its child does not duplicate questions.
- [ ] Random and Balanced are labelled comparable; every practice type is
      labelled not comparable.
- [ ] An attempt with "Avoid recently tested questions" on is labelled
      **Fresh sample** and is never compared to a strict Random attempt.
- [ ] Weakest topics, Toughest questions and Easiest questions work from
      `question_stats` + `card_state` + authored difficulty, with neutral
      priors and documented fallbacks.
- [ ] Mistakes is bounded by the same length and uses the latest graded,
      answered standing by `finishedAt DESC, id DESC` (skips are not
      mistakes).
- [ ] A focus type with a short pool fills from its ladder and shows the
      composition.
- [ ] The 40% category cap applies to Toughest/Tricky and relaxes with
      redistribution when it is infeasible.
- [ ] Passages stay whole; the header explains any difference between asked
      and shown.
- [ ] Sampling is deterministic across resume; focus selections are stored as
      ids and never re-ranked on resume.
- [ ] Saving a test does not change `card_state` or `review_log`.
- [ ] Deleting an attempt recomputes the affected `question_stats` rows; a
      skip-only row survives, and a row whose last result is deleted is
      removed.
- [ ] The partial `question_results` index exists after both a v20 → v21
      migration and a fresh create.
- [ ] Attempts record type, requested count, scope, canonical scope key,
      sampling version, exclusion mode, target topics, seed and composition;
      old attempts and snapshots still load as legacy.
- [ ] `rep*` counters update only for Random/Balanced attempts with
      `samplingVersion >= 2`; practice and legacy attempts do not touch them.
- [ ] History/Results show the type; Statistics keeps mock and practice, and
      strict and fresh, separate.
- [ ] The result page labels the attempt kind and comparability, and shows
      score and accuracy separately with correct/wrong/skipped/ungraded and
      the negative-marking note (12/4/2/2 at 1/3 marking = 59.3%).
- [ ] Representative tests show the sample size and an approximate margin
      using the shown count; short and legacy attempts do not.
- [ ] The page shows weakest topics with sample sizes, as-of-attempt titles,
      the unresolved count and the topics that were not sampled.
- [ ] It explains where the marks went with evidence (time, FSRS, skip,
      guess), using "likely/suggests" language.
- [ ] It shows time analysis, coverage, repeats, mistakes and due-in-Study
      where available.
- [ ] It gives three to five evidence-backed next actions with working links.
- [ ] Practice results never show a mock trend or claim mock comparability;
      their per-topic trend uses the recorded target set.
- [ ] The page states that the test did not change the Study schedule.
- [ ] README matches the behavior.

## 16. Decisions accepted by the owner

| # | Decision | Accepted value |
|---|---|---|
| 1 | Short scopes | Allow a labelled **short test** below 20 when the whole scope is smaller, or when the sampler cannot reach 20 from a larger scope; exclude it from comparable mock trends. |
| 2 | Default length | **20**, fast start. Remembering the last-used length can come later. |
| 3 | Focus pool short | **Fill from the type's ladder** and show the composition (`12 mistakes + 8 toughest`), rather than disabling the type. |
| 4 | Balanced allocation | **Proportional to topic size** for "Balanced"; an equal-per-topic variant is a separate "Coverage" type, not a Balanced mock. |
| 5 | Toughest category cap | **40%** of the test from one category. |
| 6 | First focus types | **Weakest topics, Toughest questions, Easiest questions and Mistakes**, plus Random. Balanced and the remaining focus types follow. |
| 7 | Practice attempts and item difficulty | **Count them toward personal item difficulty**, but label `question_stats` as "all test attempts" and keep a **representative-only** filter (`rep*` counters) for Statistics. Practice results are not a measurement claim. |
| 8 | Practice attempts in History | **Yes**, with a **Practice** badge; they never enter the mock trend. |
| 9 | Trend grouping | **Separate** Random and Balanced lines, because their variance differs. |
| 10 | Paper pace hint | Show the paper's own seconds/question only when it is plausible (**20–180 s**); otherwise default to **60 s**. |
| 11 | Tags | **Later**; `questions.tags` needs an exact-match index/table for large banks. |
| 12 | Avoid recently tested questions | **Default on** for Random/Balanced/Easiest, recorded as `exclusionMode = RECENT_3`, with a setting; such an attempt is a **Fresh sample** and is not comparable to a strict attempt. Document the small deviation from uniform sampling. |

**Settled corrections after review.** These are not new owner choices; they
settle points the plan left open, and they are binding on the implementation:

| # | Correction | Settled value |
|---|---|---|
| C1 | Deleting an attempt and `question_stats` | `question_stats` means **currently stored**. Deleting an attempt recomputes the affected rows from surviving `question_results` (all-attempt and `rep*` counters, `lastResultIsCorrect`, `lastFinishedAt`); delete a row only when `totalAttempts == 0`. Skip-only rows survive. The History delete path recomputes; paper/category/question delete sweep; restore rebuilds. |
| C2 | Pre-v21 attempts | **Out of scope.** No backfill and no pre-v21 exclusion. Legacy full-scope attempts inflate `skipCount`, so `skip(q)` and Tricky are weak until bounded attempts exist; this is documented wherever they appear. |
| C3 | `targetTopicIds` | Sorted, deduped, comma-joined Weakest/Mixed targets; **outside** the comparability key; empty for other types. The per-topic practice trend compares the recorded set, and an empty-target run cannot contribute. |
| C4 | Partial index creation | Created in **both** the v20 → v21 migration and `RoomDatabase.Callback.onCreate`; tested on a migrated database and a fresh create. |
| C5 | Sampling version and legacy count | New attempts write `samplingVersion = 2`; legacy attempts read as `samplingVersion = 1` and `requestedCount = 0`; a legacy snapshot is never resumed as a Random test. |
| C6 | Margin denominator | The margin uses the **shown** count; comparability uses the **requested** count. A passage overshoot makes the margin approximate and the label says so. |

**FSRS difficulty (accepted as guidance).** Use `card_state.difficulty` as a
**read-only, confidence-weighted prior** in focus selection, as described in
section 9.2.1. Never use it to choose a Random/Balanced mock sample. Show the
memory-vs-performance gap in Statistics as a diagnostic, not as a mastery
score.

## 17. What this means for the Statistics plan

[`statistics-plan.md`](statistics-plan.md) is still valid, with these
dependencies:

- The **Exam tab** must not be built until Test produces bounded attempts.
  Otherwise it charts 1,000-question attempts with 980 skips.
- The Exam tab must group by the full comparability key — **type, canonical
  scope, requested length, sampling version and exclusion mode** — show the
  sample size and a confidence band, and keep mock and practice families
  separate. **Strict and Fresh sample attempts are separate lines**; a fresh
  sample is never plotted against a strict Random attempt, and its band is
  labelled approximate.
- The Exam tab reads the **representative** counters (`rep*`) for mock
  accuracy and the all-attempt counters for personal/practice views. The two
  must be labelled; the same question can legitimately show two different
  lifetime accuracies.
- **`question_stats` means "currently stored".** Deleting an attempt changes
  the hardest-question cards, per-question accuracy and category weakness.
  The Statistics page must say "hardest in stored attempts", not "hardest
  ever".
- **Pre-v21 attempts are a documented limitation, not a hidden correction.**
  Their full-scope skips inflate `skipCount`; `skip(q)`, Tricky and any
  skip-rate chart are weak until bounded attempts exist. There is no backfill
  and no exclusion.
- **Test coverage** (distinct questions engaged / scope) is only meaningful
  once samples are bounded and repeated. It pairs naturally with the Cards
  tab's studied/unseen coverage.
- **Study-derived exam slices** (weak categories, due material) may be shown
  next to exam accuracy, but must be labelled as practice, not as a
  representative mock.
- **FSRS difficulty** is shown as a Study-estimated prior, not as a test
  score. The useful view is the memory-vs-performance gap (section 9.2.1):
  a hard-in-Study/high-in-Test question and an easy-in-Study/low-in-Test
  question mean different things. Do not collapse the two into one number.
- **Practice progress** is a better metric than a practice score: weak-topic
  accuracy over time, mistakes cleared, leeches resolved, at-risk material
  tested, unseen coverage. These are the numbers that show whether the focus
  types are working. The per-topic trend compares runs with the same recorded
  `targetTopicIds`; an empty-target run cannot contribute.
- **Retention by answer mode (study vs test)** stays deferred: the two tables
  are separate, and merging them would need a shared event log.
- The SRS tabs remain untouched by this work; Test never writes FSRS state.

## 18. Worked examples

**A 1,000-question subject, 20-question Random test.**
Scope = whole paper, available = 1,000, length = 20, type = Random. The
sampler shuffles the blocks, finds an exact fit at 20 if one exists (or the
smallest overshoot within 5), stores `requestedCount = 20` and
`totalQuestions = shown`, and scores out of the shown count. The 95% margin is
about ±22 points. The attempt is comparable to another 20-question strict
Random test of the same paper, not to a 100-question mock. If "Avoid recently
tested questions" was on, it is a **Fresh sample** and is not compared to a
strict attempt.

**A 1,000-question subject, 50-question Balanced test, 8 topics.**
Quotas are proportional to each topic's count; floors plus largest remainders
fill 50. Each topic is sampled internally, then the blocks are interleaved.
The score is a stratified estimate of the whole paper; the margin is about
±14 points.

**A 240-question Polity scope, 20-question Weakest topics test.**
Category weakness ranks Polity's topics; the top 3 are, say, Fundamental
Rights (42%), Parliament (48%) and Judiciary (51%). Twenty questions are
allocated across the three (roughly proportional, at least one each), and the
sheet shows the three topics and their rates. The attempt stores the three
selected category ids as `targetTopicIds` (sorted, deduped, outside the
comparability key).
The result is a Practice score, not a mock; progress is read from the three
topics' accuracy over time, and only runs with the same recorded target set
are compared.

**A 12-mistake pool with a 20-question Mistakes test.**
The pool has 12 mistakes, so the fill ladder adds 5 Tricky and 3 Toughest
questions. The sheet and result say `12 mistakes + 5 tricky + 3 toughest`.
The test is still 20 questions, and the composition is recorded. The learner
sees that the test is mostly, but not purely, mistakes.

**A 1,000-question paper with a 25-question passage, 20-question target.**
The passage cannot be split. The sampler shuffles first, then runs the exact-fit
DP; if it cannot reach 20, it may overshoot by at most
`max(1, ceil(0.25 × 20)) = 5`, so the 25-question passage is admissible and
the test shows 25. The header says "20 asked, 25 shown — passages kept whole".
A 30-question passage would exceed the 25 limit, so the sampler would stop at
the best fit at or below 20 and label the attempt "N shown (short)". A passage
larger than 150 is rejected with a specific message.

**What a 20-question Weakest-topics result looks like.**

Attempt: Practice, Weakest topics, 20 shown, 20 minutes, Polity scope of 240
questions. Results: 12 correct, 4 wrong, 2 skipped, 2 ungraded; with 1/3
negative marking on wrong answers only, score `(12 − 4/3) / 18 = 59.3%`;
accuracy on answered `12/18 = 67%`; median 48 s/question; 3 wrong answers took
over 90 s; one topic had 5 of 12 and scored 42%; 6 questions had no valid FSRS
state; 4 of the 20 are due in Study. The attempt stores the three targeted
category ids as `targetTopicIds`.

The page then says, in order:

1. **Practice · Weakest topics · 20 of 240** — "This test did not change your
   Study schedule." No mock trend.
2. **What this result means** — score 59.3%, accuracy 67%, ±22-point margin is
   not shown because practice is not comparable; "target accuracy on the three
   targeted topics: 58%".
3. **Where the marks went** — 3 knowledge gaps (wrong, slow, hard in Study),
   1 rushed (wrong, 7 s), 2 skipped, 2 ungraded, 2 fragile correct (correct but
   hard in Study).
4. **Topics** — the three targeted topics with sample sizes and as-of-attempt
   titles; the rest of the scope marked "not targeted"; any deleted question
   counted in "N results no longer resolvable".
5. **Time** — wrong answers averaged 82 s vs 44 s for correct, so the evidence
   points to knowledge gaps, not rushing, except for the 7 s wrong answer.
6. **Next actions** — (a) Study the weakest targeted topic (5/12, 42%);
   (b) review the 4 wrong questions, slowest first; (c) repeat this focus test
   in 2–3 days on the same recorded target set; (d) take a random mock to
   check transfer, because practice gains can be regression to the mean.

The same numbers on a strict Random mock would instead show a 59.3% score, a
±22 margin, a comparable trend and "take a 50-question mock for a tighter
estimate". The report changes its claims with the family, not just its labels.
