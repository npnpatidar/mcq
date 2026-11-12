# Learner-relative calibration that targets exam performance

**Purpose.** This document sets out how the app should adapt the dwell ladder to the learner, given the decision that the app exists to improve performance in a real exam and should calibrate itself toward that goal.

It is a design note, not a research brief. It states what is being optimised, what signal is trusted, how the adjustment is bounded, what has to be stored, and how a single learner can tell whether the change helped.

It is written to be read on its own, by a reviewer who has not seen the project's other documents.

---

## Settled premises

These are decisions already taken by the product owner. They are stated so that a review does not spend effort solving problems the product has chosen not to have, and so that nothing below is read as an open question.

| Premise | What it means |
|---|---|
| **The purpose is exam performance** | The app is practice for a real-world exam. Success is the learner's performance in that exam, not the fidelity of any model. Practical proxy goals: solve as many questions as possible, and retain the facts and ideas inside them. |
| **Grading is automatic** | Again, Easy, Good and Hard are assigned without user intervention. Two reasons: convenience, and removing the unintentional bias of self-grading. |
| **Manual grading stays available** | The learner can always change a grade. It is a user-facing correction, not part of the measurement, and it is not used as a fitting sample for the ladder. |
| **The learner is assumed honest** | The learner is not trying to game the algorithm; the app is for their own benefit and a wrong grade only produces a worse schedule for them. No anti-gaming machinery is wanted: no absence allowance, no invalid-timing bucket, no deferral, no monitoring of suspicious behaviour. A control such as pause is allowed and is interpreted literally. |
| **Dwell means on-screen time** | Time counts only while the question is on screen and answerable. Off-screen, backgrounded, screen-off and paused time is not counted. This is settled, including the consequence that a pause is simply a statement of "not working on this now". |
| **FSRS remains the scheduler** | The scheduler is not being replaced, retuned from scratch, or compared against alternatives. It consumes the four grades; this note is about producing those grades. The app separately fits FSRS's own weights to the learner's log. |
| **Design changes are acceptable** | The owner will accept changes to the clock, timer, storage or interface if they serve the objective. That is permission, not an invitation to redesign the product: automatic grading, FSRS, and one learner per installation are fixed. |
| **The coloured clock is an owner decision** | The question clock shows a three-band colour as a deliberate product requirement. Its behavioural risk — an honest learner can still speed up or hesitate when a colour is watching — is accepted and documented. The band is display-only, never changes a grade, and never enters a calibration label. A review may measure the effect; it cannot veto the display. |
| **One learner per installation** | There is no server and no cross-user dataset. Analysis is within-learner. Population estimates are out of scope by construction, not by caution. |
| **History is never rewritten** | A change applies to future reviews. Past rows keep the rule they were graded under, and an old review is never re-graded by a newer ladder. |

If a review concludes that one of these premises is the actual obstacle, the useful move is to say so explicitly and name the premise, rather than to design around it silently. That is a decision for the owner, and it is better made in the open.

---

## Background: how the app grades today

This section exists so the note can be read without any other document.

- **The task.** The app shows one multiple-choice question at a time. The learner selects one or more options, may declare the attempt a guess before answering, and presses Check. Correctness is exact set match.
- **Dwell.** Time is measured on the device's monotonic clock, and only while the question is on screen and answerable. It stops when the card leaves the viewport, when the app is backgrounded, when the screen is off, when the learner pauses, and when the answer is checked. Time spent off screen, away, paused or reading the explanation is never counted.
- **Reading allowance.** Before grading, a time allowance is subtracted for reading the material once: twenty visible characters per second plus three seconds per image, covering the stem, every option, and the passage title and body, rounded up to a whole second.
- **Grade.** A wrong, empty or declared-guess attempt is always Again. Otherwise the residual — dwell minus the reading allowance — decides: below c1 it is Easy, at or above c2 it is Hard, and in between it is Good. The defaults are c1 = 8 seconds and c2 = 30 seconds.
- **Manual override.** The learner can change any automatic grade before it is saved. This is a user-facing correction, not part of the measurement.
- **The scheduler.** FSRS is a spaced-repetition scheduler that takes one of four grades, updates a per-card memory stability and difficulty, and chooses the next interval so that predicted recall at the due date is near the desired retention — 0.90 by default, adjustable between 0.75 and 0.97. Predicted recall (retrievability) is computed from the card's stability and the elapsed time. The app also fits FSRS's own weights to the learner's review log.
- **Lapse.** A wrong answer on a card that had already graduated out of the short learning steps.

The ladder therefore sits upstream of the scheduler: it decides which of the four grades an attempt receives, and the grade decides the interval. Everything that follows is about placing c1 correctly for one learner, and about reporting c2 rather than moving it (§4.6).

---

## 1. The target is retention, not agreement with self-report

The ladder turns an attempt into one of four grades, and the grade sets the interval. So the ladder's output is not a description of the learner; it is **an instruction to the scheduler about how long to wait**.

That changes what "calibrated" means. A ladder is well calibrated when the grades it produces are **informative about what happens at the next review** — not when they match what the learner would have chosen. Two cards can both feel Easy and one can be forgotten in a week; a self-grade cannot distinguish them, and matching self-grades can simply encode the learner's overconfidence.

The concrete target is therefore:

> The grade a card receives at one review should predict its outcome at the next review better than the neighbouring grade does, once the scheduler's own prediction for that interval has been accounted for.

This is measurable from data the app already produces, it does not require the learner to change modes or self-grade, and it is the quantity that actually governs exam readiness: whether material is still retrievable when it is needed.

**An honest scope note, which should travel with the design.** The ladder is a small lever. Coverage of the syllabus, the amount of retrieval practice, and the use of test-style questions matter more for exam performance than the exact position of a cut-point. The calibration should be presented as tuning, not as the thing that makes the learner ready.

---

## 2. Why not simply fit the cut-points to self-grades

Self-grades are the most convenient label, and it is tempting to fit the cut-points to them directly. They are a proxy, and a biased one, for three reasons that matter here:

- **Felt difficulty is not retention.** Effort and familiarity are weak predictors of delayed recall, especially for confident learners on familiar-looking material.
- **The label is anchored by the interface.** The clock is visible during normal use, and the automatic grade is visible in the simplified mode, so a self-grade can be a reaction to the number or the proposal rather than an independent judgement.
- **Overrides are selected on disagreement.** If the only labels are the cards the learner chose to re-grade, the sample consists entirely of cases where the ladder was wrong, from both directions. Fitting to them pulls the observed Easy median up and the observed Hard median down, which narrows the middle band even when the learner's true bands are wider. Overrides are a useful check; they are not a fitting sample.

Self-grades and overrides therefore remain available to the learner and are useful as a sanity check, but they are not the fitting target and they do not decide adoption.

---

## 3. The signal: adjacent contrasts, not per-grade error

For every review the app already knows the grade it assigned, the interval it scheduled, the card's state, and — as of the change described in section 6 — the recall probability the scheduler predicted for that card at that moment. At the next review of the same card it knows whether the answer was correct. Chaining consecutive reviews of a card therefore gives, for each grade: how many attempts carried it, how many of the following attempts were correct, and what recall was predicted immediately before that following attempt.

**Why the neighbouring grade is the right comparison.** The obvious quantity is a grade's own error — observed success minus predicted recall. It is the wrong thing to act on, for a reason that is structural rather than statistical: **the grade group is defined by the cut-point being tuned.** A per-grade error therefore describes the current partition; it does not estimate what moving the cut would do. Worse, any common bias — a scheduler running above or below its target retention — moves every grade in the same direction, and would push the cut on evidence that says nothing about it.

The measured case is concrete: in the learner's own log, recall ran about seven percentage points above prediction **at every grade**. A per-grade error would have called all three grades too generous. The contrast between adjacent grades cancels that common component, and does not have to estimate it at all.

So the quantities of interest are:

- **δ1 = (Easy's error) − (Good's error)** — does the Easy label predict a better outcome than the Good label?
- **δ2 = (Hard's error) − (Good's error)** — does the Hard label predict a worse outcome than the Good label?

Where "error" means, over the paired attempts carrying that label, the mean of (outcome ∈ {0,1}) minus the predicted recall that the scheduler made immediately before the outcome attempt. Predicted recall is the right baseline because it already accounts for how long the interval was: a grade that schedules longer intervals naturally has lower success rates, and comparing raw success rates across grades would confuse "this label is too generous" with "this label waits longer".

The directions are worth stating explicitly, because they are easy to get backwards, and because the app encodes them as a table:

| Pattern | Reading | Adjustment |
|---|---|---|
| δ1 clearly negative | Easy's cards came back worse than Good's, after accounting for the fact that they were scheduled further out | **Lower c1** — a shorter Easy window |
| δ1 clearly positive | Easy's cards came back better than Good's | **Raise c1** — a wider Easy window |
| δ1 indistinguishable from zero | The Easy label carries no information beyond Good for this learner | **No change.** Prefer a wider Good band over inventing a boundary |
| δ2 clearly positive | Hard's cards came back better than Good's, so Hard is being awarded too harshly | Raise c2 |
| δ2 clearly negative | Hard is not harsh enough | Lower c2 |
| Every grade off in the same direction | Not a ladder problem | The scheduler's target retention or weights need attention |
| All grades match prediction but intervals are wide | Ladder is fine; evidence is thin | No change |

Note what the first and third rows have in common: **"no change" is a legitimate, expected result**, not a failure of the measurement. At one learner's volume it is the most likely result for a long time, and the interface should say so rather than look like a dashboard that has stopped working.

**A worked example, with the sign made explicit.** Suppose a period contains 300 Easy attempts and 70 Good attempts. Easy's error is +7 points, Good's error is +5 points, so δ1 = +2 points: Easy's cards came back slightly better than Good's, once each grade's own interval length is accounted for. The card-clustered 90% interval on that contrast spans −4 to +8 points. Nothing is done — not because the estimate is zero, but because the range is compatible with zero and with a difference too small to be worth a change.

Now suppose instead, six weeks later, Easy's error is −2 points while Good's is +5. Then δ1 = −7 points, and if the interval lay below zero this would say: Easy's material was coming back *worse* than Good's despite being scheduled further out, which is the signature of an Easy window that is too wide. c1 falls by one step, 8 s → 7 s. c2 does not move; see §4.6 for why.

The comparison is deliberately not causal. The grade was assigned from dwell, so the contrast describes how the labels behaved for this learner, not what would happen if the cut moved. Causal claims need the alternating comparison described in section 5, and even then they are weak at n = 1.

One statistical caution belongs here rather than in a footnote. Paired outcomes are not independent: a card reviewed six times contributes several correlated pairs, and cards differ in difficulty. Intervals must therefore be computed with the **card as the clustering unit** — a cluster bootstrap over cards, not an ordinary binomial interval over reviews. Treating every pair as independent narrows the interval substantially and will trigger adjustments the data do not support. In the learner's first two weeks the card-clustered half-width on the Easy/Good contrast was 5.8 points; the same data treated as independent would have claimed roughly half that.

---

## 4. The adaptation loop

Two tiers, with the prior always available as a fallback.

**Tier 1 — the prior.** Until there is evidence, the ladder is the current one: eight seconds plus the reading allowance for Easy, thirty seconds plus the allowance for Hard. This is also what a new learner starts with, and what the app returns to if adaptation is switched off.

**Tier 2 — damped adjustment.** When the evidence clears the gates below, a single cut-point moves by one capped step. The numbers are engineering defaults chosen to make a wrong adjustment unlikely rather than to be optimal; where a measurement exists it is quoted, and where none exists it is labelled a choice.

### 4.1 What a pair is, and what is excluded

A pair is one review of a card and the next review of the same card. Excluded from the fit, each for its own reason:

- **the label was overridden, or the learner grades by hand** — those rows say what the learner wanted, not what the dwell rule produced;
- **the label was Again** — Again is decided by correctness, a declared guess or an empty answer, never by the dwell ladder;
- **the attempt was a declared guess or an empty answer** — neither is a retrieval failure, and skips cluster by ladder position, which biases the very quantity being measured;
- **either row is a card's first exposure** — a first exposure has no forecast to compare against (§4.2), and it is measured while the material is still in view;
- **the scheduler's predicted recall is missing** — nothing to compare against;
- **the outcome attempt has no correctness flag** — older rows, before the column existed.

This is not a formality. In the learner's first two weeks it removed 35% of raw pairs. That is the cheapest part of the design and it should not be relaxed to buy sample size.

### 4.2 First exposures are reported, never fitted

Half of this learner's first exposures graded Easy, and a quarter were Again. That is not evidence about the Easy cut, for two independent reasons:

- **There is no forecast.** A new card has no memory state, so the scheduler has no recall probability to predict. The only available "prediction" would be the target retention the schedule itself was built from, which makes the comparison circular.
- **The initial weights are fitted on exactly that data.** The app's scheduler fitting routine estimates four per-grade starting-stability parameters from first-exposure outcomes. Feeding those same outcomes to the cut as well is double-counting one observation set.

So the first-exposure stratum is shown as a diagnostic — counts by grade, raw first-review recall against the assumed target — and labelled as *what the scheduler's own fit consumes*. It never enters the contrast, and it never moves a cut. A dedicated first-exposure experiment (varying the cut on new cards only) is a possible future study, not part of this design.

### 4.3 Readiness: precision, not volume

The first version of this note proposed waiting for a fixed number of paired outcomes. Measurement showed that number is the wrong instrument, because pairs do not accumulate steadily.

Pairs are produced by **revisiting a card**. A first exposure of a new question adds a review and no pair; a card that has been seen before produces a pair only when it comes back, and the scheduler sends it back at geometrically growing intervals — for this learner, roughly 8 days, then about 66, then about 400. In the first two weeks the daily pair counts were:

```
99  81  51  27  10   6   4   7  15   0  12   0   0
```

The opening flood is the first re-review wave. After it, the rate settles near three pairs a day, and every new question imported pushes it lower. A thousand pairs would have been roughly a year away — while the precision such a count was standing in for was already available at a quarter of it.

The gates are therefore stated in the units that matter:

| Gate | Value | Why |
|---|---|---|
| Volume floor | 300 paired outcomes | Stops a technicality becoming a decision on a handful of observations |
| Precision | at least 40 distinct cards in **each** compared class | Below this no interval-based decision is allowed |
| Resolution | 90% card-clustered half-width ≤ 5 percentage points | The gate that decides whether a 5-point threshold is even representable |
| Baseline | no scheduler weight refit since the current ladder took effect | The forecast is the yardstick; a refit moves it |
| Calendar floor | 28 days | A minimum, not the gate |

Precision is governed by the smaller class, and at this learner's label mix that is Good (18% of paired attempts against Easy's 78%). One consequence is worth noting: a cut that moves Easy *toward* Good both corrects the suspected fault and makes the next decision easier to measure.

### 4.4 Blocks and epochs

Two different objects, and conflating them is how a block design goes wrong.

- **An evidence block** is a fixed 28-day calendar window of label dates, scored once its labels have had a 90-day horizon for their outcomes to arrive. It closes early on a ladder change or a refit. Every closed block is recorded, whether it decided anything or not — including the blocks that read "insufficient evidence". Without that record, a controller that stopped running is indistinguishable from one that keeps declining to act.
- **An epoch** is the span since the current ladder took effect. All eligible pairs in the epoch are pooled for the contrast, and the readiness gates above apply to the epoch.

Pooling is not optional. A fixed calendar window avoids the length bias of counting observed pairs — a card with a 2-day interval produces a pair long before a card with an 8-day interval does, so Easy would be systematically under-sampled by a count-based window. But at three pairs a day a 28-day window holds about 84 pairs, i.e. about fifteen Good cards, which is below the precision gate. A controller that only ever looked at one block would never be allowed to act, however long it ran. Pooling pairs within an epoch keeps the calendar definition and lets the estimate reach precision.

The cost is honest: a decision lags its labels by up to 90 days, and at this learner's volume the first decision is months away. That is the price of measuring recall rather than speed.

### 4.5 The step, the bounds and the frequency

- **One step** is ten per cent of the current cut, with a minimum of one second and a cap of two seconds while the cut is within three seconds of its default. At the default 8 s that is one second.
- **One cut moves at a time**, at most once per closed block.
- **Bounds.** c1 stays between 3 and 60 seconds and at least five seconds below c2. Every output is clamped, and the clamp is the same function the app uses everywhere, so the clock bands and the grade can never disagree about the ladder in force.
- **The step size is not the lever for speed.** In this learner's log, 3.15% of reviews sit within one second of the Easy cut, so a single step reclassifies about 1.6% of attempts. A larger step would not produce better evidence; it would only make a larger unmeasured change. Precision comes from waiting, not from stepping harder.

### 4.6 Only the Easy cut is tuned in the first version

The Hard cut is displayed and never moved. Two measurements forced this. The Hard label is about 5% of paired attempts, and in fourteen days it produced eighteen usable pairs spanning seventeen cards, with a contrast interval twenty-one points wide — an interval that cannot distinguish a large effect from a large effect in the other direction. Because Hard evidence accumulates roughly fifteen times slower than Easy evidence, this is structural at this learner's pace rather than a temporary shortage.

There is also a coupling: moving c1 changes the Good group that δ2 is measured against, so the two contrasts are not independent estimators even in principle. Tuning one cut against a stable comparison group is the version that can be reasoned about.

If Hard evidence ever accumulates — a much larger bank, or a learner who routinely takes thirty seconds — the stored structure already supports turning δ2 into a second policy.

### 4.7 What keeps the loop stable

One consequence is worth accepting in advance: the signal is produced by the policy being tuned. Easy scheduling longer intervals lowers Easy's success rate, which looks like "Easy too generous", which tightens Easy, which raises its success rate. Damping and hysteresis are what keep that loop stable, and a design that skips them will oscillate on a single learner's data.

A second controller exists and must be named. The app's scheduler fitting routine estimates the Hard penalty and the Easy bonus — the two per-grade multipliers on stability growth — from the same per-review outcome likelihood the contrast is computed from. Freezing refit periods prevents the two from acting simultaneously, not the feedback: tighten the Easy cut, then the next refit absorbs whatever per-grade bias remains, and the ladder sees a clean signal it did not produce. Three cheap rules follow: a refit ends the epoch; a pending refit blocks a proposal, not merely an automatic change; and the movement in those two weights is reported beside the contrast, so a future reader cannot credit the ladder for work the optimizer did.

---

## 5. What a single learner can and cannot support

The realistic dataset is one person's history. That supports:

- within-learner comparisons across time windows;
- effect sizes with explicit uncertainty;
- an alternating design, if a causal claim is wanted: run one ladder for a block of weeks, the other for the next block, and compare outcomes on matched material — **in a balanced order (A, B, B, A, …), not alternating A, B, A, B**, because the unbalanced order gives one arm a systematically later calendar position and a learner who improves over time will make that arm look better.

It does not support population claims, per-topic ladders, or reliable estimation of more than two or three parameters. The first version fits one. A third parameter — a learner-specific reading-speed multiplier on the content-based allowance — is worth considering only when the learner has a large, length-varied history; with a few dozen reviews it will absorb noise rather than signal.

This also sets the reporting tone: "for this learner, over this window, the Easy contrast was this large, with this uncertainty, and nothing was changed". Not "the model learned the learner's pace".

Alternating comparison carries over, and this should be said plainly. Conditioning on the scheduler's prediction controls for the current stability, which is where most of a previous ladder's effect lives — but it also removes the part of the effect that operates *through* stability, and the prediction is a mediator of the interval. Calendar drift, content mix and exam proximity are untouched. So the comparison freezes the scheduler weights, the target retention, the reading allowance and the clock's visibility for the duration, and contrasts an unadjusted policy outcome.

---

## 6. What has to be stored

Most of the data already exists. The additions are small, and none of them rewrite history.

**Per review:**

- the dwell used for grading — exists;
- the grade and whether the answer was correct — exists;
- the interval and post-review state — exists;
- **grade source** — new: automatic, human-selected, or human-edited. Needed so human labels can be separated from the app's own output. This is not a nicety: without it, the first two weeks of this learner's log cannot distinguish an automatic Easy from an override, and every calibration number computed from it is contaminated;
- **the dwell's reading allowance** — new. It is subtracted before grading and then discarded; storing it makes the residual reproducible and is the only way to say where a cut sits in ordinary dwell terms;
- **the predicted recall at review time** — new, snapshotted rather than recomputed. It is derivable by chaining the log and re-running the scheduler's arithmetic, but that duplicates the scheduler in a second place, and the two will drift the moment the weights are refitted. One number written at review time is cheaper and cannot disagree with the schedule that was actually made;
- **the ladder version in force, and the scheduler weight-fit version** — new. Without them, outcomes cannot be attributed to the rule that produced them, and a comparison or a rollback is impossible;
- **whether the attempt was a declared guess or an empty submission** — the guess is already recorded; the empty submission is not, and currently looks identical to a wrong answer.

**Two small tables.**

- **A table of ladder versions:** the two cut-points, when the version took effect, why it changed (prior, manual, proposal, automatic, rollback), which version it replaced, and which evidence block triggered it.
- **A table of evidence blocks:** the window, the ladder version and weight-fit version in force, the counts and contrast for each grade, the interval, and the decision taken — *including blocks that decided nothing*. This table is what makes the loop auditable, and it is not optional: the automatic reversal rule fires on two consecutive post-change blocks, neither of which owns a version row, so a schema keyed on versions cannot store the inputs to its own reversal rule. It is also the only record of how often the controller declined to act.

**A table of study sittings** — start, end, active time, paper — so that "did this cost more time" can be asked at all. Today the active time of a sitting is measured and then discarded when the queue runs out.

**No new event stream, no calibration mode, no rewriting of past grades.** Past reviews keep the version they were graded under, and an old review is never re-graded by a newer ladder.

---

## 7. How the learner sees it, and why it should be quiet

Three surfaces are enough:

- a line in settings: the current ladder, whether it is adapting, and when it last changed;
- a short outcome summary: retention by grade against what the scheduler predicted, with the sample size stated, the Hard contrast marked "not enough evidence to judge", and the three numbers that are still short (pairs, cards per class, precision) when the app is not yet adapting;
- a pause switch and a reset to the prior.

No per-session prompts, no "we noticed you are improving" messages. The adaptation exists to make the schedule better, not to be a feature the learner has to manage. If the app is doing its job, the learner should notice nothing except that reviews land at sensible times.

One line deserves care, because it is the line most likely to be read as a bug: at this learner's volume the app may spend months saying "not adapting yet". The interface should state which of the three gates is short and by how much, so that "waiting" and "broken" are visibly different.

---

## 8. Does it actually improve performance

**The honest answer is that this design cannot establish it.** No exam outcome exists anywhere in the log. Every candidate metric is retention-at-review, which is a property of the schedule rather than of the learner's exam readiness. So the plan makes no exam-performance claim, and this section exists to bound harm rather than to prove benefit.

Three specific reasons a whole-deck lapse rate cannot be the criterion:

1. **The baseline is a setting, not a measurement.** Intervals are chosen so that predicted recall at the due date is the target — 0.90 by default. The learner's observed lapse rate was 6.2%, close to that. A "20% improvement in lapses" would therefore mean running *above* the target retention, which is a scheduler change, not a ladder improvement.
2. **The two plausible corrections move lapses in opposite directions.** Lowering the Easy cut relabels some Easy cards as Good, shortening intervals and raising lapses; the corresponding correction at the Hard cut lengthens others and lowers them. A correct calibration of the commonest fault pattern nets to approximately zero change in lapses per hundred reviews.
3. **The effect is diluted about fortyfold.** One step touches about 1.6% of reviews. Even a ten-point recall difference on exactly those cards moves a 6.2% lapse rate by 0.16 points — 2.6% relative. A 20% bar would need something like eight consecutive perfectly-correct steps.

What replaces it:

- **The decision quantity** is the contrast, re-estimated in the new epoch. A change is kept while the contrast stays inside the five-point band, and reversed if it moves outside in the wrong direction for two consecutive blocks.
- **Guardrails, with stated margins:** lapses per hundred graduated reviews no worse than 20% relative, and study minutes per review no worse than 10%. Both are policy contrasts rather than fixed characteristics — the ladder changes when cards graduate and how often they are due, so numerator and denominator move with the policy. That is acceptable for a balanced comparison and unacceptable as a control signal.
- **If an exam-facing outcome is ever wanted**, the only non-circular one available is accuracy on unseen, test-style questions sat under exam conditions, before and after. That is a study, not a feature, and it is out of scope here.

A single-learner alternating comparison is the strongest evidence available. `ladder-evaluation.md` pre-registers it as an eight-block ABBA comparison with a within-card paired recall outcome and the two cost guardrails above. Run it with automatic adaptation off; a positive result is suggestive at n = 1, not an exam-performance claim. Until it is run, automatic mode should stay off.

---

## 9. Sequencing

1. **Record provenance.** Grade source, the allowance, the predicted recall, the ladder and weight-fit versions, the block and version tables, and study sittings. No behaviour change.
2. **Report, do not adjust.** Compute the contrast by grade and show it. This is also how the first real evidence arrives.
3. **Fix the two measurement bugs** that a change of this kind would otherwise inherit: dwell is truncated per screen segment while the allowance is rounded up, and the clock's zero-second band disagrees with the grader. Neither is a calibration feature; both move the boundary the calibration reads.
4. **Propose, confirm.** Let the learner apply a suggested adjustment and compare epochs.
5. **Automate, with damping and reversal**, once the proposal path has shown the loop is stable on real data. Automatic mode is off by default.
6. **Evaluate** with the pre-registered ABBA comparison in `ladder-evaluation.md`, automatic adaptation off, before keeping the candidate cut.

Steps 1 to 3 are the ones that make everything else possible, and none of them changes a grade except at the boundary.

---

## 10. Risks

- **The loop chases noise.** Mitigated by the precision gate, interval estimates, damped steps and automatic reversal — but never eliminated at n = 1.
- **The ladder is blamed for scheduler problems.** The contrast cancels a common bias by construction; the design should say so and refuse to move a cut when every grade deviates the same way.
- **Adaptation makes the clock dishonest.** The bands must use the same ladder in force; a band computed from the prior while grading uses an adjusted ladder would teach the learner the wrong rule.
- **Silent drift.** A learner who does not inspect settings may not notice the ladder moving. The version table and the one-line settings summary exist for that; a periodic, quiet mention is acceptable, a prompt is not.
- **The feature never acts, and looks broken.** At three pairs a day the first decision is months away. Mitigated by saying which gate is short; not eliminated.
- **Two controllers, one signal.** The scheduler's weight fitting estimates the same per-grade multipliers the ladder is trying to influence. Mitigated by refit boundaries and by reporting both; not eliminated.
- **Overfitting to one season of study.** A ladder tuned on one paper may be wrong for the next. Versions are per learner, and a reset is one tap.

---

## 11. What we are asking a reviewer to test

The questions this note is least equipped to answer on its own, in the order they would change the design:

1. **Is the adjacent contrast the right estimand, or is a proper scoring rule better?** The alternative is a per-grade Brier or log-loss score against the scheduler's prediction, possibly with a hierarchical model. Does the difference of adjacent means discard information that a scoring rule would keep — in particular, does it hide a case where both grades are badly calibrated in the same direction but the *boundary* between them is correct?
2. **Is clustering by card sufficient?** A card's outcomes are correlated across its whole history, and cards within a topic are correlated too. A two-level model (card within topic) may be necessary before any interval is believed. The first two weeks gave 382 pairs over 334 cards — a very low pairs-per-card ratio, which may make the card-level clustering conservative enough; that is an empirical question.
3. **Is epoch pooling defensible?** Fixed calendar blocks keep the design free of length bias but cannot reach the precision gate at this learner's volume, so pairs are pooled across blocks within an epoch. Does pooling across blocks with the same treatment reintroduce drift, and should the epoch instead be capped in length?
4. **Is the alternating design sufficient to make any causal claim?** The grade is assigned from dwell, so the contrast describes label behaviour rather than an intervention effect. Is the balanced block comparison enough, or is the honest conclusion that this is a controller which must be validated by outcome and cannot be validated by inference at n = 1?
5. **Should there be a first-exposure experiment?** Half of first exposures grade Easy and a quarter are Again, and they are excluded from the fit for good reasons. A separate study that varies the Easy cut on new cards only, and compares recall at eight days, would answer the question this design explicitly cannot. Is that worth running, and what would it cost in the learner's time?
6. **How should the two controllers be separated?** The scheduler's weight fitting estimates the Hard penalty and the Easy bonus from the same outcomes. Is a fixed-weight window the right way to keep them apart, or should the ladder's contrast be computed on a schedule replayed with a single fixed weight set?
7. **Should the reading-speed multiplier be a parameter?** It is a plausible third parameter — the content-based allowance assumes twenty characters per second for everyone — but at small samples it will absorb noise. Is there a sample size at which it becomes estimable, and can that be established from the data rather than guessed?

The note deliberately does not ask for population estimates, per-topic ladders, or a model of the learner's ability. One learner's history cannot support them, and pretending otherwise would be the most likely way for this design to mislead.

---

## Appendix A — the first real measurement, and what it implies

Everything below comes from one learner's log: **920 reviews, 334 cards, 14.1 days**, with the shipped defaults in force and no scheduler refit yet. It is included because the constants in this document were revised once these numbers existed, and a reviewer should be able to see which conclusions rest on data and which on judgement.

| Quantity | Value | Implication |
|---|---|---|
| Paired attempts surviving the exclusions | 382 of 586 raw | The exclusion set costs 35%, not 90% — the design is viable |
| Easy / Good / Hard pairs | 297 / 67 / 18 | Good is the scarce comparison group |
| Easy contrast, 90% interval | +1.8 points, [−3.6, +8.0] | No evidence the Easy cut is misplaced today |
| Hard contrast, 90% interval | −0.9 points, [−12.5, +8.9] | The Hard cut cannot be judged at this volume |
| Recall: Easy / Good / Hard | 94.6% / 92.5% / 94.4% | The grade is **not** monotone in recall here |
| Predicted recall: Easy / Good | 87.5% / 87.2% | A common bias of about seven points, which the contrast cancels |
| Easy's share of paired attempts | 78% | The Easy window is firing often |
| Median elapsed time to the outcome: Easy / Good | 7.4 days / 2.3 days | Any "maturity" threshold on elapsed time would filter the two groups very differently, and none of the Easy pairs reached 30 days |
| Implied reading allowance, recovered from the data | median 14 s, interquartile 9.5–20 s | The Easy cut sits near 22 s of ordinary dwell |
| Reviews within one second of that cut | 3.15% | One step reclassifies ~1.6% of attempts |
| Lapses per graduated review | 6.2% | Close to the 10% implied by a 0.90 target |
| Paired attempts generated per day | 99, 81, 51, 27, 10, 6, 4, 7, 15, 0, 12, 0, 0 | Pair generation decays; ~3/day in steady state |

Three of this document's earlier constants did not survive contact with these numbers, and the reasons are worth recording:

- **A window of 500 paired outcomes** was replaced by fixed calendar blocks plus epoch pooling, because pairs arrive in a burst and then trickle, and a 28-day window holds roughly 84 of them.
- **A fixed minimum of paired outcomes before adapting** was replaced by a precision gate, because the two-parameter contrast was already resolved to 5.8 points at 382 pairs, while the count proposed — a thousand — would have taken about a year to reach at three pairs a day.
- **A requirement that Easy outcomes be at least thirty days old** was deleted outright: none of the 297 Easy pairs qualified, and the requirement filtered Easy and Good by different amounts, which is selection on the grade being compared.

Two things the measurement could not settle, and which therefore remain judgement: whether the card is the right clustering unit at this pairs-per-card ratio, and whether pooling across blocks within an epoch reintroduces drift. Both are in section 11.

---

## References

These are the works that bear on the two assumptions the design rests on: that response time is a usable but imperfect signal of retrieval, and that time-based scoring raises fairness questions that a purely statistical criterion will not settle.

- De Boeck, P., & Jeon, M. (2019). An overview of models for response times and processes in cognitive tests. *Frontiers in Psychology*, 10, 102. — What response-time models can and cannot identify about the processes behind an answer, and why a time threshold is a decision rule rather than a measurement of ability.
- Gilbert, J. B., Young, W. S., Himmelsbach, Z., Ulitzsch, E., & Domingue, B. W. (2026). Conditional dependencies between response time and item discrimination: An item-level meta-analysis. *Educational and Psychological Measurement*, 86(5), 1039–1070. — Response time and item difficulty are not interchangeable; the relation varies by item, which is the reason the calibration uses outcomes rather than times as its target.
- Ofqual (2025). *Time limits and speed of working in assessments: when, and to what extent, should speed of working be part of what is assessed?* — The conditions under which speed is a legitimate part of the construct, and the fairness costs of letting time decide a score.
