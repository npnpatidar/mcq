# Reply to the fourth review

**What this file is.** A written response to `ChatGPT_answer_fourth.md`, recording which of its
recommendations were accepted, which were not, and on what grounds. Written to be readable
without the plan, because the review's central objection — that the prediction paired with an
outcome must be a forecast for the same moment — was correct and reshaped the design.

The review's verdict was: approve the target, approve measurement and reporting, revise the
statistics before allowing automatic changes. That verdict is accepted. The design as it now
stands is measurement-first for three releases, and the automatic stage is off by default and
gated on evidence rather than on a release date.

Two things happened after the review was written that change several of its recommendations: the
statistical design was rebuilt around **adjacent contrasts** rather than per-grade error, and the
learner's own log was measured — 920 reviews over 334 cards in 14 days. Where a recommendation
was overtaken by a number, that is said explicitly rather than smoothed over.

---

## 1. The prediction timestamp — accepted, and the review was right that it matters

The review's strongest point: a forecast made at review *t* for the due date is not a forecast
for the moment the learner actually next answers, and pairing the wrong one makes the residual
partly a measure of schedule adherence.

Accepted, with one simplification. The design stores **one** forecast on the row that carries the
answer: the recall probability computed from the card's state immediately before that answer
("next-review forecast" in the review's terms). The "scheduled-due forecast" is not stored as a
second column. The reason is not that the distinction is unimportant — it is that the due-date
forecast has no consumer in this design: it is not used in the contrast, not used in the
proposal, and not used in the rollback. Storing a second number that nothing reads invites a
later reader to average the two.

What the due-date forecast would have been good for is diagnosing adherence — "the learner
answers four days late, so the scheduler's own target is not the target in practice". That
diagnostic is computable on demand from the interval and the elapsed time, both of which are
already stored, so it does not need a column.

The failure mode the review identified is real and is handled differently: the forecast is not
re-read from preferences at write time. The view model grades from a configuration snapshot
taken when the session loaded; if the store rebuilt its own scheduler, a mid-session cut edit or
weight refit would attach a forecast that never graded anything. The row now carries the
configuration it was actually graded under (`ladderVersion`, `fsrsFitVersion`).

## 2. Brier score and calibration by prediction range — accepted as diagnostics only

Accepted. The report shows the mean contrast (the decision quantity), plus Brier score per grade
and the existing predicted-versus-actual bins. Neither is a decision criterion; both are
labelled as diagnostics.

The review's caution that "a mean error of zero can hide miscalibration in opposite directions"
is the reason the contrast is used rather than a grand mean: the difference between two adjacent
grades cannot be zero-with-large-cancellation in the same way a single mean can.

## 3. Distinct-card counts, and the count minimum — accepted, and made the actual gate

Accepted and strengthened. The old note said "at least 30 paired outcomes for a grade", which the
review correctly called under-specified. The gates are now stated in both units: a volume floor
(300 pairs), a per-class distinct-card minimum (40 in each compared class), and a required
precision (90% half-width ≤ 5 points).

The 40-card number is not a guess. In the learner's log, 382 pairs over 334 cards gave a
card-clustered half-width of 5.8 points; the same data treated as independent would have claimed
roughly half that. The distinct-card requirement is what keeps that correction in place.

## 4. Topic or passage clustering — accepted as a stated limitation, not implemented

This is the one statistical recommendation the design does not act on, and it should be recorded
plainly rather than buried.

A two-stage bootstrap (topics, then cards within topics) requires enough independent groups to
estimate between-group variance. The learner's library has two papers. Even splitting by
category, there are too few groups for a second stage to be informative, and a two-level model
fitted on a handful of groups produces an interval that is precise about the wrong thing.

So the design keeps card-level clustering, states that it may understate uncertainty where cards
share a topic, and reports the number of distinct topics alongside the interval. If a later
library has enough independent categories, the second stage becomes worth adding; the statistic
is computed in one place, so that is a change to one function.

## 5. Rolling windows and repeated testing — accepted; this removed the rolling window entirely

The review's most useful operational objection. The old note evaluated "the most recent 500
pairs, capped at 90 days" whenever it was consulted, which is precisely the repeated-peeking
problem: overlapping windows, a fresh look at almost the same data, and a 90% interval that
eventually excludes zero by construction.

Replaced with what the review preferred: **non-overlapping evidence windows**. A block is a fixed
28-day calendar window of label dates, closed once its labels have had a 90-day horizon for
outcomes. A decision may be taken at most once per closed block, and every closed block is
recorded — including the ones that decide nothing.

One deviation from the review's recommendation, and the reason. Non-overlapping windows have a
volume problem at this learner's scale. Pairs are generated by revisiting cards, and the daily
counts in the first two weeks were 99, 81, 51, 27, 10, 6, 4, 7, 15, 0, 12, 0, 0 — the opening
flood, then about three a day. A 28-day block therefore holds roughly 84 pairs, of which about
fifteen are Good, which is below the precision gate. A controller that could only look at one
block would never be allowed to act.

So the design distinguishes **blocks** (fixed windows, the audit and rollback unit) from an
**epoch** (the span since the current ladder took effect, over which pairs are pooled for the
estimate). Pooling within an epoch keeps the calendar definition — so no length bias — while
letting the estimate reach precision. This is a deviation from "non-overlapping windows" and is
flagged as an open question for a reviewer rather than presented as settled.

## 6. Follow-up is selected by the review process — accepted, and sharpened

Accepted. The report breaks follow-up into not-yet-due, overdue, suspended or removed, and
observed; missing is never counted as a failure.

The design goes one step further, because the measurement showed the original rule was biased.
Follow-up rates were Easy 57%, Good 62%, Hard 61%, which looks like Easy cards disproportionately
never returning. They do return — the median Easy outcome gap is 7.4 days and the history was
fourteen days long. The gate is therefore counted **against due-ness**: a label whose next
review is not yet due is *pending*, not missing. Counting elapsed time instead would have
penalised the grade with the longest intervals — Easy — permanently.

## 7. The direction table is evidence for a hypothesis, not a guaranteed correction — accepted

Accepted, and the wording changed throughout. The rule now reads as evidence in favour of testing
a change in a direction; validation happens on a later block; rollback uses a pre-declared
metric.

The pre-declared metric is the contrast itself, re-estimated in the new epoch. Not the Brier
score, not the lapse rate. Those can disagree, and the review is right that switching between
them after seeing the result is how a rollback becomes decorative.

## 8. Study time belongs in the decision context — accepted, but not as a co-primary

Accepted that time is a real cost and that a lapse reduction bought with much more study is not
obviously a win. Not accepted as a co-primary outcome: two primaries with no combination rule is
not a decision rule, it is two chances to declare success.

The design instead treats time as a **guardrail** with an explicit margin: study minutes per
review no worse than +10%, lapses per hundred graduated reviews no worse than +20%. A change is
reversed if it breaches either; neither can be used to claim success.

A stronger reason to demote the lapse rate entirely — which the review did not have — is that the
effect is diluted about fortyfold. Measured: 3.15% of reviews sit within one second of the Easy
cut, so one step touches about 1.6% of attempts. Even a ten-point recall difference on exactly
those cards moves a 6.2% lapse rate by 0.16 points, or 2.6% relative. A superiority bar on that
metric is not conservative; it is unreachable. And the two plausible corrections move lapses in
opposite directions, so a correct calibration nets to roughly zero.

## 9. The alternating design — accepted with ABBA; card-level allocation rejected

Accepted: randomised or balanced order, fixed scheduler settings, a pre-registered analysis, and
the result described as suggestive.

Rejected: card-level allocation with matched cards staying on one ladder for the whole
comparison. The review offers it as a way to avoid repeatedly switching a card's policy. The
objection is that it creates a worse problem than it solves:

- **The two arms would contain different cards.** Matching on topic and maturity is an
  approximation, and at 334 cards splitting the library halves the per-arm count below the
  precision gate before the trial starts.
- **Carryover does not disappear, it moves into the outcome definition.** The outcome is
  conditional on the scheduler's own prediction, and that prediction encodes the card's entire
  history — including reviews taken under the other ladder before allocation.
- **It costs the thing the product needs.** A card permanently on the inferior arm is
  deliberately scheduled worse for weeks, on one learner, to answer a question the design cannot
  answer at n = 1 anyway.

Blocks on the same cards with a balanced order keep every card under both ladders, keep the
comparison within-card, and match the machinery that already exists.

## 10. Data additions — accepted, with one rejected addition

Accepted: grade source, ladder version, weight-fit version, the snapshotted forecast, the
allowance, guess and empty-submission flags, both the automatic and the saved grade, a version
table, an evidence-block table, and a study-sitting table.

The evidence-block table is the addition the review asked for as a "decision-window ID", made
concrete. It has to be a table rather than a column because the reversal rule fires on two
consecutive blocks that own no version row; a schema keyed on versions cannot store the inputs to
its own reversal rule.

Rejected: an explicit foreign key linking a review to its next review. The pairing is
reproducible from `(paperId, questionId, reviewedAt, id)`, which is also the index the query
needs, and a self-referencing key on the largest table in the app would have to be maintained on
every insert and every restore. The link is a query, not a column.

Also rejected: a stored scheduled-due forecast, for the reason in §1.

---

## What the review could not know

The review assumed a rolling-window controller operating on an unknown but implicitly adequate
volume. Measuring the log changed four constants and deleted one requirement outright:

| Constant | Before | After | Why |
|---|---|---|---|
| Evidence window | rolling 500 pairs, 90 days | fixed 28-day blocks + epoch pooling | rolling windows are repeated peeking; 28 days alone cannot reach precision |
| Readiness | 1,000 pairs | 300 pairs + 40 cards/class + ±5 points | 382 pairs already give ±5.8; 1,000 would take about a year at three pairs a day |
| Easy maturity | outcomes ≥ 30 days old | deleted | 0 of 297 Easy pairs qualified, and the rule filtered Easy and Good by different amounts |
| Cuts tuned | both | Easy only | Hard produced 18 pairs and a ±12.5-point interval; it is 5% of labels |

And one thing the review could not have anticipated, which validates its own §2 objection to
per-grade error: observed recall ran **+6.7 points above prediction at every grade**. A per-grade
error would have called all three grades too generous and pushed both cuts on a scheduler-wide
bias. The adjacent contrast cancels it. That is now a worked empirical case in
`calibration.md` rather than a theoretical argument.

## The honest state of the evidence

The measured contrast is +1.8 points with a 90% interval of −3.6 to +8.0, and the grade is not
monotone in recall (Easy 94.6%, Good 92.5%, Hard 94.4%). The most likely correct behaviour of
this controller for months is **no change**, and the interface is designed to say so with the
three numbers that are short, so that "waiting" and "broken" look different.

That is also the strongest argument for the review's own sequencing recommendation, which the
plan follows: provenance, then reporting, then two measurement fixes, then proposals, then
automation — with nothing automatic until at least one proposal has been applied and two
post-change blocks have closed.
