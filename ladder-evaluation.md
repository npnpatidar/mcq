# Ladder evaluation: a pre-registered ABBA comparison

**Status:** draft protocol. It is not active until the owner freezes the
registration fields below, merges this document, and records the block-1 commit.
The app does not allocate arms or run a trial automatically. The controller from
phase 5 stays off for the duration.

**What this can answer.** Whether one pre-registered Easy-cut step changes
practice recall on the same cards under a balanced calendar comparison, and
whether it costs more lapses or study time. It cannot answer whether the learner
will score better in an exam: the review log contains no exam outcome, and
retention-at-review is a property of the schedule.

This protocol implements §6 of `plan.md`. It is deliberately a document and a
fixed analysis rule, not an allocation engine.

---

## 1. Registration fields

These fields are frozen before block 1. If a value is missing, the protocol is
not registered and block 1 must not start.

| Field | Value |
|---|---|
| Owner sign-off | _pending_ |
| Arm A (current) | Easy cut `c1_A = 8 s`; Hard cut `c2 = 30 s`; proposed, owner must confirm |
| Arm B (candidate) | Easy cut `c1_B = 9 s`; `c2` unchanged; one step, proposed, owner must confirm |
| Trial scope | one selected paper, or the whole library if the owner records that choice here |
| Block length | 28 days, aligned to the 04:00 study-day boundary |
| Blocks | 8 (four per arm) |
| Order | A B B A A B B A |
| Proposed block 1 start | 2026-12-01 (first 04:00 boundary on/after that date) |
| Proposed block 8 end | 2027-07-13 (exclusive) |
| Frozen build | `git rev-parse HEAD` of the merged app at block 1, recorded below |
| Analysis version | `ladder-evaluation.md` at the same merge commit |
| Registered by / date | _pending_ |

**Why 8 blocks when the plan says at least 6.** Four blocks of an ABBA sequence
give exact calendar balance (A in positions 1, 4, 5, 8; B in 2, 3, 6, 7; both
average position 4.5). Six blocks cannot give both arms the same average
position. Eight 28-day blocks cost 32 weeks and satisfy the “at least six, at
least three weeks” floor without pretending a six-block imbalance is harmless.

The dates are proposed, not authorized. If the owner picks a different start,
every boundary moves together, the order and count do not.

---

## 2. The intervention and the frozen settings

The only difference between arms is `c1`, the Easy cut. Arm B is one policy step
from Arm A: `max(1, round(0.10 × c1_A))` seconds, capped at 2 s near the default.
A and B use the same `c2`; the Hard cut is not being tested.

Every one of these is frozen for all eight blocks:

- `SchedulerConfig.fastSeconds` / `slowSeconds`: set to the arm's values, never
  edited mid-block;
- FSRS weights and `fsrsFitVersion`: no “Optimize FSRS parameters”, no restore
  to defaults, no refit;
- `desiredRetention` (record the value, normally 0.90), `maxIntervalDays`,
  `relearnMs`, `leechThreshold`, `dayStartHour`;
- the reading allowance and its `ceil`, and whether the question clock is
  visible;
- automatic ladder adaptation **off** (phase 5), so the controller cannot move
  a cut during the trial;
- new-card introduction **off** for the duration, so the card population does
  not change; due reviews continue;
- no question or passage content edits, no imports, no paper deletion. An edit
  resets a card and removes it from the paired analysis;
- no change of scope paper, app build, or analysis code.

Any violation is a protocol deviation. Record it with its date and which blocks
it touched. A mid-trial refit ends the trial rather than being absorbed into an
arm.

---

## 3. Who is in the analysis

A card is included when all of these hold:

1. it graduated out of `NEW`/learning before block 1;
2. it has at least one eligible review in an A block **and** at least one in a
   B block;
3. its content hash did not change during the trial;
4. its paper is in the registered scope.

An **eligible review** is a study review with a submitted, non-empty selection
and no declared guess. Skips and guesses are not retrieval outcomes. Do not
condition on `predictedRecall`, on the grade source, or on whether the grade was
edited: this is an unadjusted policy comparison, and those are consequences of
the arm.

Cards seen in only one arm are excluded from the paired contrast and counted in
the attrition table. New cards introduced during the trial are excluded from
the primary analysis; new-card introduction is off, so this should be zero.

---

## 4. Outcomes and formulas

### 4.1 Primary: within-card paired recall

For card *i* in arm *a*:

```
y_ij  = 1 if review j's selection matched the key exactly, else 0
p_ia  = mean over eligible reviews j of y_ij          (one value per card/arm)
d_i   = p_iA - p_iB                                    (only cards in both arms)
Delta = mean over cards of d_i
```

`Delta` is the primary estimate: the average within-card change in recall from
Arm A to Arm B. Every card contributes once, so a card reviewed fifty times does
not outweigh a card reviewed twice. A positive `Delta` favours B.

Report the arm-level raw recall as well, but the decision uses `Delta`.

### 4.2 Primary interval

Cluster by card: 5,000 BCa resamples of the per-card `d_i`, 90% two-sided,
seed `20261111`, using the same card-clustered routine as
`Calibration.contrast`. Report the interval and its half-width as the achieved
MDE.

- at least 40 paired cards: BCa interval;
- 20–39 paired cards: Welch t interval on card means, 90%;
- fewer than 20 paired cards: no interval and no adoption decision.

A degenerate sample (all `d_i` identical) claims no interval, for the same
reason phase 2 does.

### 4.3 Cost outcomes and margins

Both are arm-level policy contrasts, not fixed learner characteristics. They are
reported with their own intervals, and neither is used to steer the ladder.

| Outcome | Formula | Non-inferiority margin (B vs A) |
|---|---|---|
| Study minutes per review | `sum(study_sessions.activeSeconds) / 60 / eligibleReviews` in the arm's blocks | B/A ≤ **1.10** |
| Lapses per 100 graduated reviews | `100 × count(rating = AGAIN AND stateBefore = REVIEW) / count(stateBefore = REVIEW)` in the arm's blocks | B/A ≤ **1.20** |

A lapse is exactly the `Fsrs.kt` definition; new and learning reviews are in
neither numerator nor denominator. Sessions are matched to blocks by
`startedAt`. If a session spans a boundary, assign it to the block containing
its start and record that choice once.

### 4.4 What is not an outcome

- Exam marks, exam readiness, or “true ability”: not in the log.
- The calibration δ1 itself: it is the controller's decision quantity, not an
  independent evaluation of the policy. Report it by arm as a mechanistic
  covariate, not as the primary result.
- Any deck-level aggregate not listed above. Adding one after seeing the data is
  a new analysis, reported separately and labelled exploratory.

---

## 5. Order, carryover and calendar position

The order is A B B A A B B A. With 28-day blocks, the arms are calendar-balanced
in position. The ABBA pairing also balances the immediate carryover transitions
in both directions; it does not eliminate carryover, because a card's stability
carries across a boundary.

No washout blocks. A washout would remove the very reviews the paired contrast
needs, and at this volume would make the trial unfinishable. Carryover is a
stated limitation, not a modelled quantity. Do not condition on predicted
recall or stability in the primary analysis; that would remove the part of the
effect that operates through the schedule.

---

## 6. Censoring, missingness and follow-up

- The outcome is the review's correctness at the moment of review; there is no
  delayed primary outcome and therefore no outcome-horizon censoring.
- A block contributes reviews timestamped inside it, using the same 04:00 day
  boundary as the rest of the app. Reviews at a boundary go to the later block.
- A card that is suspended, deleted, or content-edited after contributing to
  one arm is excluded entirely from the paired analysis and listed in
  attrition. Do not keep half a pair.
- If a card has no eligible review in one arm, it is not imputed. Report the
  paired-card count and the excluded-card count.
- Missing `wasCorrect`, `stateBefore`, or session time is reported, not
  back-filled. A review with a missing correctness flag is not eligible.
- The trial does not follow cards after block 8. A later outcome is not part of
  this comparison.

---

## 7. Analysis unit and model

The unit is the **card**. The analysis is the within-card paired contrast in
§4.1, with the card as the cluster. There is no block fixed effect: the policy is
constant inside a block, so with three or four blocks per arm the block
indicator is collinear with treatment. Do not fit both.

No covariate adjustment, no mixed model, no per-grade stratification in the
primary analysis. Report the unadjusted policy outcome. Subgroup tables (grade
mix, interval band, category) are descriptive and labelled exploratory; they do
not change the decision.

The analysis is reproducible from the review log, the session table and the
frozen settings. Record the exact query/code version at block 1. If the analysis
is run by hand, commit the script that produced the numbers; if it is a
notebook, commit the notebook. A result without its code is not registered.

---

## 8. Stopping rule

There is **no interim efficacy stop**. Peeking for benefit and stopping when the
interval first excludes zero inflates the false-positive rate; eight blocks are
short enough to wait.

Stop the trial early, and do not adopt B, if any of these occurs:

1. a cost guardrail is worse than its margin for **two consecutive blocks**;
2. the phase-5 safety valve would have rolled the arm back under its ordinary
   rule;
3. the frozen settings in §2 change, including a refit;
4. a content edit or deletion invalidates so many cards that fewer than 20
   pairs remain possible;
5. the owner stops for a reason recorded before the data are inspected.

Stopping for 1–4 is a protocol outcome, not a failure. Report the blocks
completed, the reason, and the then-current intervals.

---

## 9. Decision and reporting

At the end, report:

- paired cards, eligible reviews per arm, and the attrition table;
- `Delta` with its 90% interval and achieved MDE;
- arm-level recall, lapses per 100, minutes per review, with intervals;
- the calibration δ1 by arm, as a mechanistic side note;
- every protocol deviation and the stopping rule, if used;
- the exact build and analysis commit.

Adopt B only if **all** of the following hold:

1. the primary interval excludes zero in B's favour;
2. both cost guardrails are within their non-inferiority margins;
3. no stopping rule fired;
4. the owner accepts the result as suggestive at n = 1.

Otherwise keep A. “No decision” is a valid outcome when the interval is too
wide; it is not evidence that A and B are equivalent.

Even a positive result is **suggestive at n = 1**, not a randomised trial and
not an exam-performance claim. Report it with the interval, the frozen settings,
and the deviations. Do not describe it as “proven”, “validated”, or “better for
learning”.

---

## 10. The ten pre-registered items

1. **Primary outcome and formula:** within-card paired recall difference,
   `Delta = mean_i(p_iA - p_iB)`, §4.1.
2. **Cost outcome and margin:** study minutes per review, B/A ≤ 1.10; lapses per
   100 graduated reviews, B/A ≤ 1.20, §4.3.
3. **Block count, length and dates:** 8 × 28 days, proposed 2026-12-01 through
   2027-07-13, §1.
4. **Order:** A B B A A B B A, §5.
5. **Frozen settings:** weights, retention, allowance, clock, new cards off,
   auto adaptation off, no content edits, §2.
6. **Card inclusion:** graduated before block 1, one eligible review in each
   arm, unchanged content, in scope, §3.
7. **Censoring and follow-up:** immediate review outcome, boundary rule, whole
   card excluded on edit/deletion, no imputation, §6.
8. **Analysis model:** within-card paired contrast, card-clustered BCa/t, no
   block fixed effect, no covariates, §7.
9. **Stopping rule:** no efficacy peek; stop for two guardrail breaches, valve
   trigger, frozen-setting change, or insufficient pairs, §8.
10. **Exact code version:** the block-1 `git rev-parse HEAD` and the analysis
    commit, recorded in the registration table, §1.

---

## 11. Deviations and amendments

Before block 1, an amendment is a dated entry in this file and a new commit; the
owner re-signs the registration table. After block 1, do not edit the protocol
to fit the data. A necessary change is recorded as a deviation with its date,
the blocks affected, and the reason, and the affected blocks are reported both
with and without it.

Typos that do not change a formula, a date, an inclusion rule or a decision rule
may be fixed without re-registration; note them in the commit message.

---

## 12. What would make this protocol wrong

The protocol is only as good as the assumption that the arms differ in the cut
and nothing else. It is not equipped to separate the ladder from a simultaneous
FSRS refit, from a change in exam proximity, or from a learner who studies
differently in one arm. Those are reasons to freeze the settings and report the
limitations, not reasons to add covariates after the fact.

If the owner wants an exam-facing claim, the only non-circular design is
accuracy on unseen, test-style questions under exam conditions before and after
the trial. That is a separate study, not this comparison.
