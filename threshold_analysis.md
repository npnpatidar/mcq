# Study grading thresholds: what the code does, what the log says, what to change

Analysis of `mcqapp-backup.zip` (app 0.0.51, dbVersion 26, 2026-10-28 → 2026-11-13) against the
grading implementation in `app/src/main/java/com/mcqapp/`. The question it answers: the auto-grader
labels **63 % of all reviews Easy** (64.8 % of the rows that can be replayed); Anki's documentation
treats Easy as the uncommon answer. Is that a bug, a tuning problem, or the rule itself?

Short answer: not a bug. The rule is implemented exactly as specified, and it produces this
distribution because reading time is the dominant term in the Easy threshold. The adaptive ladder
cannot correct this — even at its floor it leaves more than half of the full sample Easy, and in this
backup it has never run at all. The fix is to change what Easy means, not to tune a number.

> **Revision note.** This version replaces the earlier draft's claim that the 975 pre-versioning rows
> could not be reconstructed. `content.json` contains all 33,930 questions with their elements, so
> the reading bonus can be recomputed; that expands the analysable sample from 114 to **812** rows.
> The R2 sketch has been corrected (the reading bonus must stay on the Hard cut explicitly), R1 no
> longer contradicts R2, and the missing `ladderVersions` row is treated as its own finding.

---

## 1. Data used

| | |
|---|---|
| Review rows | 1 089 (425 distinct cards, 17 days, 2026-10-28 → 2026-11-13) |
| Rows with full grading metadata (`ladderVersion = 1`) | 114, all within a 21.8-hour window |
| Rows with `ladderVersion = 0` | 975 |
| Rows that can actually be re-scored | **812** = 114 v1 + 698 v0 with non-null `wasCorrect` |
| Rows that cannot be graded | 277 v0 rows with null `wasCorrect` (written before that field existed) |
| Grading source | `gradeSource = AUTO` on all 114 rows that carry the field; no row has `EDIT`/`CHOSEN` |
| Guesses / skips | 36 / 2 overall; 10 / 2 in the 114-row subset |
| Question content | 33,930 questions in `content.json`, all with `question_elements`/`options_elements` |

### 1.1 What the older rows actually contain

The earlier draft said `wasCorrect`, `stateBefore`, `predictedRecall` and `readingAllowanceSeconds`
were null on all 975 v0 rows. That is not what the export says:

| Field | v0 non-null | v0 null |
|---|---:|---:|
| `wasCorrect` | 698 | 277 |
| `stateBefore` | 662 | 313 |
| `wasGuess` | 698 | 277 |
| `predictedRecall` | 0 | 975 |
| `readingAllowanceSeconds` | 0 | 975 |
| `gradeSource` | 0 | 975 |
| `proposedRating` | 0 | 975 |
| `wasSkipped` | 0 | 975 |

So 698 older rows are fully gradeable if the reading bonus is recomputed. The 277 rows without
`wasCorrect` cannot be re-scored and are excluded from every simulated distribution below; they are
still included in the raw whole-log counts where noted.

### 1.2 Recomputing the reading bonus

`content.json` is not limited to the four attempts in the window. Recomputing
`ceil(chars / 20) + 3 × images` over stem, options, passage body and title matches the stored
`readingAllowanceSeconds` on **114/114** v1 rows exactly. Applied to the 698 v0 rows, it reproduces
**696/698** stored ratings (99.7 %); across all 812 scorable rows the rule reproduces **810/812**
(99.75 %). The two exceptions are v0 ids 451 and 714; 714 is a wrong answer stored as Good, which no
version of the rule explains, and both are plausibly later edits or timing anomalies. They do not
change any conclusion.

### 1.3 Why the 114-row window is not the primary sample

The v1 window is two-thirds first exposures, which are systematically less likely to be Easy:

| v1 `stateBefore` | Rows | Easy share |
|---|---:|---:|
| NEW | 75 | 45.3 % |
| REVIEW | 21 | 76.2 % |
| LEARNING | 14 | 57.1 % |
| RELEARNING | 4 | 75.0 % |

The whole scorable log is **64.8 % Easy**, not 54.4 %. The 114-row window is useful for
version-specific checks (it is the only window with `predictedRecall`, `gradeSource` and stored
thresholds), but it is not a representative sample of the distribution. Distribution and
simulation results below use all 812 rows, with the 114-row result retained where the earlier draft
used it, so the difference is visible.

---

## 2. What the implementation actually does

`com.mcqapp.domain.Study` (`app/src/main/java/com/mcqapp/domain/SpacedRepetition.kt`):

- `FAST_SECONDS = 8`, `SLOW_SECONDS = 30` (lines 293, 296).
- `easyThresholdSeconds(...)` (line 382) = `config.fastSeconds + readingBonus`, where
  `readingBonus = ceil(chars / 20.0) + 3 s per image`, counting stem text, every option, and the
  passage body and title.
- `dwellLadder(...)` (line 413) lifts **both** cuts by the same bonus, so
  `hardSeconds = slowSeconds + bonus` and the gap between the cuts stays 22 s.
- `inferGrade(...)` (line 322): wrong / skip → Again; `dwell in 1 until fastSeconds` → Easy;
  `dwell >= slowSeconds` → Hard; otherwise Good. The guess check is in `autoGrade`, not
  `inferGrade`.
- `StudyViewModel.kt:793-808` calls `autoGrade` with `fastSeconds = ladder.easySeconds` and
  `slowSeconds = ladder.hardSeconds`, so the bonus is part of both cuts.

So for a question with `n` characters of text and `i` images, with `b = ceil(n/20) + 3i`:

```
Again  : wrong, guessed, or skipped
Easy   : correct, and  1 <= dwell < 8 + b
Good   : correct, and  8 + b <= dwell < 30 + b
Hard   : correct, and  dwell >= 30 + b
```

The lower bound matters: `dwell == 0` (timing not captured) is **not** Easy under `inferGrade`.
The UI's `dwellBand` is a separate function: it returns `NEUTRAL` at 0, so the two are not
"consistent at the boundary" as the earlier draft said. In this backup the only `dwell == 0` row is
a skip and therefore Again, so the sample does not expose the divergence.

### 2.1 The logged "reading allowance" is not the reading allowance

`StudyViewModel.kt:809` stores

```kotlin
readingAllowanceSeconds = ladder.easySeconds,   // = 8 + b
```

while `ReviewLogEntity.readingAllowanceSeconds` (`app/src/main/java/com/mcqapp/data/local/Entities.kt:326`)
is documented as "The reading allowance that was subtracted, in seconds". Two inaccuracies:

1. it is **not** subtracted anywhere — `inferGrade` compares `dwell` against the passed cut;
2. it **includes** `fastSeconds`, so the export's value is the whole Easy threshold, not `b`.

`b = stored - 8`. This matters for anyone analysing the export: the natural reading
(`dwell - allowance < 0` ⇒ Easy) happens to work, because subtracting `8 + b` from a cut at `8 + b`
is the same comparison, but the Hard cut is then `stored + 22`, not `30`. Reading the column at face
value produced a false "the log disagrees with the rule" result during the first pass — a concrete
argument for fixing the field.

### 2.2 Reproduction check

Applying the rule in section 2 to the 114 rows reproduces the stored ratings exactly:

| | Again | Hard | Good | Easy |
|---|---|---|---|---|
| Rebuilt from `elapsedSeconds` + `readingAllowanceSeconds` | 13.2 % | 9.6 % | 22.8 % | 54.4 % |
| Stored `rating` column | 13.2 % | 9.6 % | 22.8 % | 54.4 % |

Counts: 15 / 11 / 26 / 62 of 114. The 15 Again rows are 11 wrong answers (2 of them also submitted
empty) and 4 correct answers marked as guesses; the 2 skips are inside that set.

Across all 812 scorable rows the replay is 810/812; the stored and rebuilt distributions are:

| | Again | Hard | Good | Easy |
|---|---|---|---|---|
| Stored | 10.8 % | 8.1 % | 16.3 % | 64.8 % |
| Rebuilt | 11.0 % | 8.1 % | 16.0 % | 64.9 % |

The v1 rows carry `gradeSource = AUTO`, so "no manual overrides" is true there. The v0 rows do not
carry `gradeSource`; the 2 mismatches are v0, so the correct statement is "no overrides are visible
in the versioned window," not "nothing in this log was manually overridden."

Whole-log distribution for reference: Again 159 (14.6 %), Hard 75 (6.9 %), Good 167 (15.3 %),
Easy 688 (63.2 %), n = 1 089. The whole log includes the 277 unscoreable rows; the scorable subset is
slightly more Easy-heavy (64.8 %).

---

## 3. Why Easy dominates

The Easy window is not 8 seconds. It is `8 + b`, and `b` is large: in the 114-row subset the stored
allowance (i.e. `8 + b`) has median **22 s**, range 14–38 s. So the Easy requirement is typically
"answer correctly within 22 seconds".

Learners answer *during* that reading budget, not after it. Define `slack = dwell - (8 + b)`, the
time left over after reading. On the 114 rows:

| percentile | p10 | p25 | p50 | p75 | p90 |
|---|---|---|---|---|---|
| slack (s) | −13.5 | −10.0 | −3.5 | +4.5 | +25.0 |

Median slack is −3.5 s: the typical answer is submitted three and a half seconds *before* the
reading budget expires. More than half of all answers land inside a window whose width is set almost
entirely by how long the question is, not by how quickly it was solved.

The effect of each lever, simulated on all 812 scorable rows (Again fixed at wrong/guess/skip):

| Easy cut | Hard cut | Again | Hard | Good | Easy |
|---|---|---|---|---|---|
| **current** `8 + b` | `30 + b` | 11.0 | 8.1 | 16.0 | **64.9** |
| ladder floor `3 + b` | `30 + b` | 11.0 | 8.1 | 27.2 | **53.7** |
| `0 + b` | `30 + b` | 11.0 | 8.1 | 38.4 | **42.5** |
| **absolute 6 s** | `30 + b` | 11.0 | 8.1 | 72.8 | **8.1** |
| **absolute 8 s** | `30 + b` | 11.0 | 8.1 | 63.1 | **17.9** |
| absolute 10 s | `30 + b` | 11.0 | 8.1 | 53.4 | **27.5** |
| absolute 8 s | absolute 30 s | 11.0 | 19.2 | 52.0 | 17.9 |

Two conclusions follow directly:

1. **Within the "additive bonus" family, no value of `fastSeconds` can make Easy rare.** Even at
   `fastSeconds = 0` — better than the ladder's own floor of 3 — Easy stays at ~43 % on the full
   sample. The ladder floor of 3 leaves ~54 %. The earlier draft's 25 %/39 % figures came from the
   114-row window; on the full sample the structural problem is larger, not smaller.
2. **An absolute cap does.** Requiring the answer within 8 s of the question appearing, regardless
   of length, drops Easy to ~18 % on this data. That is the only lever in the table that changes the
   shape of the distribution rather than shifting it.

A scaled bonus is not a middle ground. `0.5b` leaves 49.0 % Easy and `0.25b` leaves 35.8 % on the
full sample; only a bonus near zero approaches the absolute-cap range. If the goal is "Easy is
uncommon," the additive family must be abandoned.

The cost is intentional and worth stating plainly: with an absolute cap, a 300-character stem can
almost never be Easy. That is the point. If reading alone takes 15 s, then a 20-second answer is not
"no mental effort" — it is reading plus a normal decision, which is what Good is for.

For comparison, the 114-row window gives: current 54.4 %, `3 + b` 38.6 %, `0 + b` 24.6 %, absolute 6 s
0.9 %, absolute 8 s 12.3 %, absolute 10 s 16.7 %, absolute 8 s / absolute 30 s 12.3 % Easy and
21.9 % Hard. Those were the numbers in the earlier draft. They are correct for that window, but the
window is first-exposure-heavy and understates both the current Easy share and the Easy share that
survives any additive cut.

### 3.1 Corroboration from interval outcomes

The scheduler already treats these labels as different recall events: median `intervalDaysAfter` is
0 for Again, 1 for Hard, 2 for Good and 8 for Easy (n = 159 / 75 / 167 / 688). The maximum scheduled
interval in the log is 80 days, but it does not sit on the most-reviewed card: that card has 13
reviews and its last interval is 11 days; the 80-day rows are cards with two reviews. So 63–65 % of
scheduled reviews were pushed out by a multiplier applied mostly on reading speed, but the long
intervals are not yet evidence about mature cards. No card has been reviewed more than 13 times, and
the median Easy interval is 8 days, so most of those pushes have not come back to be graded. That is
precisely why the label definition matters more than the resulting retention now.

### 3.2 Same-card repeats and prior exposure

A card seen again in the same session can be answered faster from memory, so repeats could inflate
the Easy rate. They do, but only slightly:

| Group | Rows | Easy share |
|---|---:|---:|
| v1 first-in-window | 100 | 54.0 % |
| v1 repeat-in-window (all < 1 h apart) | 14 | 57.1 % |
| Full sample, first exposure | 422 | 62.8 % |
| Full sample, any prior exposure | 390 | 66.9 % |
| Full sample, repeat gap < 1 h | 53 | 62.3 % |
| Full sample, repeat gap > 1 day | 293 | 67.9 % |

Excluding the 14 v1 repeats changes 54.4 % to 54.0 %. On the full sample, an absolute 8 s cut gives
**18.0 % Easy for first exposures and 17.7 % for repeats** — essentially identical. The repeat effect
is real (about +4 points) but it does not explain the distribution, and it does not change the
recommendation.

There is a stronger composition effect inside the v1 window: cards with any earlier review are
71.7 % Easy (33/46), while cards never seen before are 42.6 % Easy (29/68). That is why the v1
window's 54.4 % is lower than the full log's 64.8 %; it is not evidence that the rule behaves
differently on repeats. For a clean calibration, stratify NEW vs REVIEW and exclude same-session
learning-step repeats. The simulator already treats both groups almost identically under an absolute
cap.

---

## 4. Why the adaptive ladder will not fix this

`LadderPolicy` (`app/src/main/java/com/mcqapp/domain/stats/LadderPolicy.kt`) proposes moving only
`config.fastSeconds`: 10 % of the current cut per decision (`STEP_FRACTION`, line 33), clamped to
`MIN_EASY_SECONDS = 3` … `MAX_EASY_SECONDS = 60` (lines 37-38), with `MIN_GAP_TO_HARD = 5`.
`Calibration.Contrast.direction` (`Calibration.kt:137-142`) will ask for `LOWER` when Easy retention
underperforms Good by at least 5 points — the correct correction, and the one this data needs.

But the gate before any of that can happen:

| Requirement | Value | Source |
|---|---|---|
| Usable label/outcome pairs | ≥ 300 | `Calibration.kt:45` |
| Distinct cards per class (Easy, Good) | ≥ 40 | `Calibration.kt:46` |
| Confidence half-width | ≤ 0.05 | `Calibration.kt:48` |
| Follow-up observed among due | ≥ 70 % per class, and ≤ 10 pp gap | `Calibration.kt:41` |
| Calendar days in epoch | ≥ 28 | `Calibration.kt:33` |
| Closed 28-day block | must exist | `LadderPolicy.kt:129` |
| Outcome horizon | 90 days | `Calibration.kt:36` |

In this backup:

- `ladderVersion = 1` covers **114 rows over 21.8 hours**, and the epoch therefore cannot have 28
  calendar days;
- **usable pairs = 0.** A pair needs a v1 label and a v1 follow-up that both carry `predictedRecall`.
  Only 39 v1 rows have `predictedRecall`, and every *usable-grade* v1 label (Good/Hard/Easy, non-NEW)
  is the last review of its card. There are 14 v1 labels with later v1 rows, but all 14 are Again
  (13 NEW, one REVIEW), so none can serve as a usable label;
- `study.json` has empty `ladderVersions` and `ladderBlocks`. Empty blocks mean the controller has
  never recorded a proposal. Empty versions are a separate invariant violation (below).

### 4.1 The missing version row is its own finding

`settings.json` says `ladder_version = 1`, and the reviews carry `ladderVersion = 1`, but
`ladderVersions` is empty. `StatsStore` computes:

```kotlin
val versionStart = if (version > 0)
    db.ladderDao().version(version.toLong())?.effectiveFrom ?: 0L
else 0L
```

With no version row, `versionStart = 0`, so `epochStart = 0`, and `LadderPolicy.evaluate` returns
`NOT_READY` immediately:

```kotlin
if (report.epochStart <= 0L || config.ladderVersion <= 0) {
    return Evaluation(Status.NOT_READY)
}
```

The immediate blocker is therefore not only "21.8 hours is less than 28 days"; the epoch has **no
anchor at all**. `LadderController.run()` calls `ladderStore.currentVersion()`, which repairs a
missing row with `effectiveFrom = System.currentTimeMillis()`, so the clock starts at the next
controller run. The grading path can write `ladderVersion = 1` from settings without ever ensuring
the row exists. That invariant gap deserves its own bug report; it is not evidence that the
controller ran and produced nothing.

So the controller is dormant by construction: with a 90-day outcome horizon before the first closed
block can be scored, plus 28-day blocks, no automatic correction is possible for roughly four to
five months after the epoch actually starts — and by then the rule is still capped at the same floor,
which, as section 3 shows, leaves Easy above 50 % on the full sample.

`automatic_ladder_adaptation = true` in `settings.json` is therefore not a safety net for this
distribution. It is a slow refinement of a cut that starts in the wrong place.

---

## 5. Against Anki's documented behaviour

From <https://docs.ankiweb.net/manual/studying#answer-buttons>:

- **Again** — "incorrect, or you didn't recall": be strict; typically **5–20 %** of reviews.
- **Hard** — correct, but "you had doubts" or took a long time.
- **Good** — correct "with some mental effort"; the most common answer; typically **80–95 %**.
- **Easy** — correct "with no mental effort". The manual treats it as the uncommon case; the word
  "sparingly" is an editorial inference from the bands, not a quotation from the page.
- Anki also documents a simpler option: **Again + Good only**.
- And on pacing: "if you can't answer within about 10 seconds, it's probably better to move on".
  That is advice about when to reveal the answer, not a proposed grade threshold.

The bands overlap and are per-button guidance, not a distribution that must sum to 100 %. The
qualitative point still holds: Good should dominate, and Easy should not.

Current state on the full sample (replay): Again 11.0 % (**in band**), Hard 8.1 % (**defensible** —
Anki's Hard is for doubt or slowness and this is a small share), Good 16.0 % (**far below** the
80–95 % band), Easy 64.9 % (**inverted** — the rare label is the most common one). The v1 window is
13.2 / 9.6 / 22.8 / 54.4; the same shape.

The failure is not "too much Easy" in isolation. It is that the middle of the ladder — the label that
means "I worked it out" — has been vacated. Every answer that arrives inside the reading budget is
classified as effortless, so Good only receives the leftovers: answers somewhere between reading
speed and 22 s past the end of the budget.

Absolute-cap variant on the full sample: Again 11.0 %, Hard 8.1 %, Good 63.1 %, Easy 17.9 %. Good is
not yet in Anki's 80–95 % band, but every remaining gap is occupied by Again and Hard, both of which
are legitimately earned (wrong answers, and answers slower than 30 s past reading). Easy is rare,
Good is dominant, and the ordering is correct.

**Be explicit about the target.** If the target is Anki's 80–95 % Good band, R2 does not reach it:
with Again 11 % and Hard 8 %, Easy would have to be near zero. The Again/Good-only alternative does
reach it (Again 11.0 / Good 89.0) and is less code, at the cost of losing the Hard label and its
1-day interval. If the target is "Easy rare, Good modal, Hard still available for doubt/slowness,"
R2 is the better fit. The two targets are not the same, and the recommendation should say which one
it is optimizing.

---

## 6. Recommendations

### R1 — Fix the logged field (do this regardless of R2)

`StudyViewModel.kt:809` should record the quantity its name and doc comment claim. The robust form,
valid under both the current rule and R2, is:

```kotlin
readingAllowanceSeconds = (ladder.hardSeconds - schedulerConfig.slowSeconds).toInt()
```

That equals `b` today (`hard = slow + b`) and still equals `b` after R2 (`hard = slow + b`,
`easy = fast`). The earlier formula `(ladder.easySeconds - fastSeconds)` is correct only before R2;
after R2 it is always 0.

Preferably record the thresholds themselves, since that is what an analyst needs to audit a grade:

- add `easyThresholdSeconds` and `hardThresholdSeconds` (schema 26 → 27, `Entities.kt`,
  `AppDatabase.kt` migration, `Backup.kt` read/write);
- keep `readingAllowanceSeconds` as the bonus, but note that its meaning changes from `8 + b`
  (historical) to `b` (new). If you want true continuity, either migrate old rows
  (`readingAllowanceSeconds - 8`, only if `fastSeconds` was 8 when they were written) or add a new
  column and leave the old one alone. Do not silently change the meaning of a populated column.

On the backup format: `McqBackupFormat.VERSION = 2` and readers accept any version ≤ `VERSION`.
Adding optional fields with defaults is backward-compatible; bumping to 3 makes older app versions
refuse the backup. Decide which compatibility you want, but do not bump without saying so.

Evidence: section 2.1. The current value cannot distinguish "the question is long" from "the learner
took 22 seconds", which is the exact distinction any future calibration review needs, and it already
caused a wrong conclusion during the first pass. Cost: realistically 6–8 files including migration
and backup tests, not ~30 lines across 4.

### R2 — Make Easy an absolute cap

In `SpacedRepetition.kt`, keep the reading bonus on the Hard end only, and extract it so the Hard cut
does not depend on the Easy cut:

```kotlin
fun readingBonusSeconds(question: Question, passage: Passage?): Long = ...

fun dwellLadder(config, question, passage): DwellLadder = DwellLadder(
    easySeconds = config.fastSeconds,
    hardSeconds = config.slowSeconds + readingBonusSeconds(question, passage)
)
```

Then in `StudyViewModel.kt:793-808` pass `fastSeconds = schedulerConfig.fastSeconds` and
`slowSeconds = ladder.hardSeconds`.

**Do not** implement the earlier sketch (`easyThresholdSeconds = config.fastSeconds` with
"`dwellLadder` unchanged"). `dwellLadder` derives the Hard cut as
`slowSeconds + (easy - fastSeconds)`; with `easy == fastSeconds` that becomes `slowSeconds`, and the
reading bonus disappears. The literal implementation produces 13.2 / 21.9 / 52.6 / 12.3 on the v1
window (and 11.0 / 19.2 / 52.0 / 17.9 on the full sample), not the intended distribution.

Predicted distribution on the full sample: Again 11.0 / Hard 8.1 / Good 63.1 / Easy 17.9, versus
11.0 / 8.1 / 16.0 / 64.9 today. Easy becomes rare, Good becomes the modal answer, and the eight-day
interval stops being the default outcome (subject to the FSRS caveat below).

Why 8 s rather than 6 or 10, on the full sample: 6 s leaves 8.1 % Easy (strict, but not
"unreachable"), 8 s leaves 17.9 %, and 10 s leaves 27.5 %. 8 s is a reasonable "rare but observable"
cut. The earlier claims that 6 s is effectively unreachable (0.9 %) and 10 s is at the top of rare
(16.7 %) were artefacts of the 114-row window. Anki's 10-second pacing advice is about when to give
up, not a grade threshold; it is a weak justification for 8 s.

Three further consequences the earlier draft missed:

- **Bump the ladder version/epoch.** R2 changes the grading rule without changing the cut. If old
  `8 + b` labels and new `8` labels share `ladderVersion = 1`, calibration pools them and the
  evidence is contaminated. Write a new version row/epoch for the rule change, or add a separate
  grading-rule version.
- **Revisit the bounds.** `MAX_EASY_SECONDS = 60` was designed for a base cut with a reading bonus.
  Under an absolute cap the effective ceiling is lower than that: `MIN_GAP_TO_HARD` rejects a
  proposal above `slowSeconds - 5` = 25 s. A 25 s Easy cut yields 64.0 % Easy on this sample — the
  old distribution — so the ladder can still walk the fix back one block at a time. Tighten the
  ceiling (e.g. 12–15 s) or cap upward steps; do not rely on the nominal 60 s guard.
- **The clock changes meaning.** `dwellBand` colours the first 8 s as Easy on every question. On a
  long passage question, the learner may still be reading at 8 s while the clock has left the Easy
  band. That is intended under an absolute cap, but it is a UX change, and it can change behaviour.

The in-sample replay holds `elapsedSeconds` fixed. Changing the visible clock can change when people
answer, so 17.9 % is a counterfactual under unchanged behaviour, not a causal forecast. The
"eight-day interval stops being the default" claim is also not simulated through FSRS; the interval
mapping depends on the scheduler's stability update, not only on the label.

Alternative if a four-button auto-grade is not wanted: emit only Again/Good from `autoGrade`
(Again 11.0 / Good 89.0 on the full sample, both inside Anki's bands) and keep the ladder purely for
the on-screen clock. This is less code but loses the Hard label, which the scheduler currently maps
to a 1-day interval. It is the only variant that actually meets the 80–95 % Good target.

### R3 — Give Hard a doubt signal instead of only a clock

Anki's Hard also covers "correct, but I had doubts". The database has no such signal today. Add a
count of selection revisions to the paused-question state, persist it on `review_log`, and thread it
into `autoGrade`:

```
Again : wrong / guess / skip
Easy  : correct, revisions == 0, dwell < 8
Hard  : correct, and (revisions > 0 or dwell >= 30 + b)
Good  : everything else
```

Evidence: section 5 — the current rule cannot express doubt at all, so a learner who changes answer
twice and lands on the right option within eight seconds is still graded Easy.

**Concrete flaw in the rule as stated:** for a multi-correct question the learner must select
several options, so the selected set changes at least once even on a confident answer. Under
"increment when the selected option set changes", every multi-correct question has `revisions > 0`
and is therefore Hard. Normalize by the number of correct options, count only changes after a
complete selection, or restrict the doubt rule to single-correct questions. Revisions can also be
misclicks or corrections, not doubt. Cost: one state field, one column (same migration as R1), one
parameter; it is the only recommendation that needs UI plumbing. Optional.

### R4 — Do not rely on the ladder for this; revisit its floor if you keep it

Given R2, the ladder's only remaining job is trimming an absolute 8 s cut. The nominal range is
3–60 s, but `MIN_GAP_TO_HARD` clips the upper end to `slowSeconds - 5` = 25 s, and 25 s is already
back to 64.0 % Easy. That is a much better-posed problem only if the ceiling is lowered; note:

- the ladder cannot fix the current distribution: on the full sample its floor (`3 + b`) still leaves
  53.7 % Easy, and even `0 + b` leaves 42.5 %. The earlier "~39 % at the floor" was the v1-window
  figure; the full sample is worse;
- with the Easy cut no longer including `b`, the step at the default is `stepSeconds(8) = 1` s
  (10 % of 8 s = 0.8 s, rounded to at least one second), so the first correction is 8 → 7; that is a
  12.5 % move in dwell terms, still small, but larger than `STEP_FRACTION` suggests when reading the
  decision report;
- the readiness gate (300 pairs, 40 cards per class, 28 days, 90-day horizon) means no decision for
  months, and with Easy at ~18 % of labels, the 40-cards-per-class floor is harder to clear, not
  easier;
- the epoch is currently unanchored (section 4.1), so the timeline starts only when the controller
  repairs the missing version row.

If the pilot is too small to ever clear 300 pairs, consider lowering `MIN_PAIRS` for a first
correction, or leaving the ladder off and reviewing the thresholds manually against the backup
export. Lowering it weakens the precision safeguard the policy was built around; say so explicitly.

### R5 — Two small correctness notes

- `inferGrade` uses `dwellSeconds in 1 until fastSeconds`, so `dwellSeconds == 0` (timing not
  captured) is not Easy; it grades Good unless the row was skipped. `dwellBand` returns `NEUTRAL` at
  0, so the two are **not** consistent at the boundary. Untimed rows should be excluded from any
  calibration sample rather than silently counted. `Calibration.Row` currently has no
  `elapsedSeconds` field, so this requires adding it and an exclusion reason.
- 66 of 1 089 reviews took more than 60 s (max 279 s), median 15 s. That is compatible with Anki's
  "move on after ~10 s" advice being ignored, and worth a soft nudge — but under R2 the nudge should
  fire at `hardSeconds` (`30 + b`), not at `slowSeconds` (30), or it will interrupt long questions
  while the learner is still reading. The nudge is a study-habit change, not a grading bug.

---

## 7. Verification plan for R1 + R2

- `app/src/test/java/com/mcqapp/SpacedRepetitionTest.kt` and `StudyDwellBandTest.kt` encode the
  current ladder; update expectations for the absolute Easy cut and add a long-stem case
  (`b = 20` ⇒ Easy still requires `dwell < 8`, and Hard requires `dwell >= 50`, not `>= 30`).
- Add a round-trip assertion in the backup test for the new `readingAllowanceSeconds` meaning. The
  earlier check (`dwell < 8` ⇔ `rating == 4` for a row) is false: wrong answers, guesses and skips
  under 8 s are Again, and `dwell == 0` is not Easy. Use the conditional predicate:

  ```kotlin
  wasCorrect && !wasGuess && !wasSkipped && dwell >= 1
      ⇒ (rating == EASY ⇔ dwell < 8)
  wasCorrect && !wasGuess && !wasSkipped
      ⇒ (rating == HARD ⇔ dwell >= 30 + b)
  ```

- `LadderControllerTest` / `CalibrationTest` may need fixture updates because R2 changes the label
  distribution; the gate constants themselves are unchanged. If R4 is implemented, add a step-size
  test at `fastSeconds = 8`.
- Build per `AGENTS.md`: `. apk-build/env.sh` then
  `sh gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin $MCQ_AAPT2_OVERRIDE --dependency-verification=lenient --no-daemon`.
  Robolectric/SQLite suites do not run on aarch64; CI is authoritative.
- Replay check after shipping: re-export and confirm the Easy condition on rows written **after** the
  new version/epoch. Do not apply it to archived `8 + b` rows; they will fail by design. Use the
  recomputed bonus from `content.json` for v0 rows, or the stored thresholds for new rows.

---

## 8. Limitations of this analysis

- 812 rows is a much better sample than 114, but it is not the whole log: 277 v0 rows lack
  `wasCorrect` and cannot be graded. Those rows are 25.6 % Again (71/277), so the scorable subset is
  slightly less Again-heavy and more Easy-heavy than the raw log. The direction of every comparison
  is robust; the exact percentages are conditional on the scorable subset.
- 17 days and at most 13 reviews on one card is still a short history. There are **0 usable
  calibration pairs**, and no observed forgetting. The claim "Easy is over-used" rests on the label
  distribution and Anki's documented intent, not on retention outcomes.
- The v1 window is first-exposure-heavy (75 NEW / 21 REVIEW) and is not representative of the log;
  all distribution and simulation figures above use the full scorable sample, with the v1 numbers
  retained only for comparison. The earlier draft's 54.4 % base understated both the current Easy
  share and the Easy share surviving additive cuts.
- `wasSkipped` is null on all v0 rows, so the replay treats it as false. A v0 row that was skipped
  but marked correct would be misclassified; that is unlikely, and the 810/812 reproduction rate
  bounds the damage. The two mismatches (ids 451 and 714) are unexplained; 714 (wrong stored as Good)
  cannot be produced by any version of the rule.
- The counterfactual replays hold `elapsedSeconds` fixed. Changing the clock colour at 8 s can change
  when learners answer, so the absolute-cap distribution is not a causal forecast.
- The backup has no `ladderVersions` row despite `ladder_version = 1` and 114 v1 reviews. That is an
  invariant violation, not merely a young epoch; the calibration timeline cannot be dated from this
  export.

---

## Appendix — reproducing the numbers

From the extracted backup (`study.json`, `content.json`), with the reading bonus recomputed for v0:

```python
import json, math, re, statistics as stats
from collections import Counter

content = json.load(open('content.json'))
questions = {q['id']: q for p in content['papers']
             for c in p.get('categories', []) for q in c.get('questions', []) or []}

TAG = re.compile(r'<[^>]*>|&[#A-Za-z0-9]+;')
WS = re.compile(r'\s+')
def clean(s): return WS.sub(' ', TAG.sub(' ', str(s))).strip()
def text(els):
    out = []
    for e in els or []:
        if e.get('type') == 'text': out.append(clean(e.get('content', '')))
        elif e.get('type') == 'image': continue
        elif e.get('type') == 'table':
            out.append(' '.join(clean(x) for row in e.get('content') or [] for x in row))
        elif e.get('type') == 'math':
            out.append(TAG.sub('', e.get('mathml', '') or e.get('content', '') or ''))
        else: out.append(clean(e.get('content', '')))
    return WS.sub(' ', ' '.join(out)).strip()

def bonus(q):
    n = len(text(q.get('question_elements')))
    for o in (q.get('options_elements') or {}).values(): n += len(text(o))
    images = sum(e.get('type') == 'image' for e in q.get('question_elements') or [])
    for o in (q.get('options_elements') or {}).values():
        images += sum(e.get('type') == 'image' for e in o)
    return math.ceil(n / 20) + 3 * images

rl = json.load(open('study.json'))['reviewLog']
rows = []
for r in rl:
    if r['wasCorrect'] is None or r['questionId'] not in questions: continue
    b = (r['readingAllowanceSeconds'] - 8
         if r['readingAllowanceSeconds'] is not None
         else bonus(questions[r['questionId']]))
    rows.append((r, b))                       # 812 rows

v = [r for r in rl if r['readingAllowanceSeconds'] is not None]   # 114 rows
slack = [r['elapsedSeconds'] - r['readingAllowanceSeconds'] for r in v]
print(len(rows), len(v), stats.median(slack), stats.quantiles(slack, n=10))
```

Alternative-cut replay (Again fixed at wrong/guess/skip; v0 `wasSkipped` is null, so only `True`
counts):

```python
def sim(rows, easy, hard):
    c = Counter()
    for r, b in rows:
        if r['wasCorrect'] is not True or r['wasGuess'] or r['wasSkipped'] is True:
            c['Again'] += 1; continue
        e = r['elapsedSeconds']
        c['Easy' if e < easy(b) else ('Hard' if e >= hard(b) else 'Good')] += 1
    n = len(rows)
    return {k: round(100 * cnt / n, 1) for k, cnt in c.items()}

sim(rows, lambda b: 8 + b, lambda b: 30 + b)   # current
sim(rows, lambda b: 8,     lambda b: 30 + b)   # R2
```

Usable calibration pairs (0 today):

```python
by = {}
for r in rl:
    by.setdefault((r['paperId'], r['questionId']), []).append(r)
pairs = 0
for rs in by.values():
    rs.sort(key=lambda r: (r['reviewedAt'], r['id']))
    for lab, out in zip(rs, rs[1:]):
        if lab['ladderVersion'] != 1 or lab['gradeSource'] != 'AUTO': continue
        if lab['rating'] == 1 or lab['wasGuess'] or lab['wasSkipped']: continue
        if lab['stateBefore'] == 'NEW' or out['stateBefore'] == 'NEW': continue
        if lab['predictedRecall'] is None or out['predictedRecall'] is None: continue
        if out['wasCorrect'] is None: continue
        pairs += 1
```
