# Study dwell thresholds — research brief

**Prepared for:** learning-science / data research review
**Subject:** how the app should decide that an answer was Easy, Good or Hard from the time it took
**Status:** the current rule is a plausible default, not a validated one. This document asks for a better one.

**In brief**

- The app can grade a study answer from how long it took: below a card-specific *easy point* is Easy, at or above a *hard point* is Hard, in between is Good. A wrong answer, an empty answer or a declared guess is always Again.
- Both points are a fixed pair of constants (8 and 30 seconds) plus an estimate of reading time from text length and image count.
- Those grades feed a spaced-repetition scheduler, so the thresholds decide *when the learner sees the card again*. They are not cosmetic.
- The constants ignore who is reading, what kind of card it is, and how many times it has been seen. The app already stores enough to do better: dwell time, the learner's own grades in the other mode, correctness, guess flags, per-card history, content shape and card state.
- We are asking for two things: a defensible rule or family of rules, and an evaluation plan we can run on existing logs before changing anything.

---

## 1. Why this document exists

The app has a study mode that can grade an answer automatically from the time spent on the question. That automatic grade is not cosmetic: it feeds the spaced-repetition scheduler, so it decides when the learner sees the card again. Today the two thresholds that drive it are a pair of constants (8 seconds and 30 seconds, plus an allowance for reading) that were chosen as a sensible default. We have no evidence that they are right, and some reason to think they are not, especially for slow readers, long questions and first-time material.

We would like the research team to help answer three things:

1. Is response time a defensible basis for an automatic grade at all, and under what conditions?
2. If so, what should the rule be — what variables, what form, what values?
3. How should we evaluate it using the data the app already stores, before shipping anything?

---

## 2. The product in one page

- An offline Android app for multiple-choice question (MCQ) practice. Questions are grouped into papers and categories, with optional passages; a question has a stem, a small set of options (commonly four), one or more correct options, an explanation, and optional images, tables and mathematical formulas.
- **Study mode** shows one question card at a time. The learner selects an answer and taps Check. The screen then shows the correct answer and explanation, and the learner moves on.
- Scoring is exact-match: for a multi-correct question, any subset or superset is wrong. An MCQ exam culture applies, so "negative marking" logic is familiar to users.
- **Two grading modes.**
  - *Classic:* the learner reveals the answer and taps one of four grades — Again, Hard, Good, Easy. Their own judgement is the label.
  - *Simplified* (the default): the app proposes a grade automatically from correctness, a declared-guess switch, and the time spent. The learner can change the proposed grade before moving on.
- **Scheduling** uses FSRS-6, a modern spaced-repetition model. Every review writes a rating (1–4) and updates two memory estimates for the card: *stability* (roughly, how many days until recall decays) and *difficulty* (roughly, how hard the item is for this person). Intervals are derived from those.
- **Consequence that matters for this brief:** the automatic grade is the scheduler's input. A threshold that is too generous makes cards look better known than they are and stretches intervals; a threshold that is too strict shortens them and inflates the daily workload. Threshold quality is therefore a scheduling-quality question, not just a labelling question.
- Everything runs on the device. There is no server, no account and no cross-user data. Each installation holds only its own learner's history.

---

## 3. The current rule, exactly

### 3.1 What "dwell" measures

The app runs a question clock that starts when the card becomes visible and the answer is still hidden. It **pauses** when the answer is revealed (so reading the explanation is not counted), when the card is scrolled out of view, when the app goes to the background, and when the learner taps the clock themselves. It resumes when the conditions are met again. Banked time accumulates across segments. The value is stored in **whole seconds**, and zero means "not timed" rather than "answered instantly".

So dwell is meant to approximate *reading plus deciding*, excluding explanation reading and interruptions. It includes motor time (taps) and any thinking the learner does away from the clock only if the clock was running.

### 3.2 The ladder

Two user settings define a thinking-time budget in seconds: a *minimum easy answer threshold* (default 8) and a *hard answer threshold* (default 30). Both are clamped to 1–3600 and the hard value cannot be below the easy value.

A reading allowance is added to both ends:

    reading seconds = ceil(total visible characters / 20) + 3 seconds per image

where "total visible characters" counts the stem, every option, the passage title and body (when a passage is shown), and "per image" counts one image in each of those places. Twenty characters per second is about 197 words per minute.

    easy point = easy setting + reading seconds
    hard point = hard setting + reading seconds

Because the same allowance is added to both ends, the width of the Good band always equals the difference between the two settings (22 seconds at defaults). The Good band therefore never disappears, however long the question.

### 3.3 The bands

| Dwell | Automatic grade |
|---|---|
| 0 seconds (no timing) | Good (treated as unknown, not fast) |
| 1 second up to, but not including, the easy point | Easy |
| easy point up to, but not including, the hard point | Good |
| hard point and above | Hard |

Three shortcuts override the ladder: an empty selection, a wrong answer, or an answer the learner declared a guess all grade **Again** regardless of speed.

### 3.4 Worked examples at default settings

| Card | Reading seconds | Easy below | Good | Hard from |
|---|---|---|---|---|
| Bare card, almost no text | 0 | 8 s | 8–29 s | 30 s |
| Typical single question, ~300 characters | 15 | 23 s | 23–44 s | 45 s |
| Long stem with a passage, ~600 characters and one image | 33 | 41 s | 41–62 s | 63 s |

### 3.5 Where the rule is visible

- The settings screen exposes the two numbers, described as "minimum easy answer threshold" and "hard answer threshold".
- The result card reports the dwell and the proposed grade, and offers a change-grade control.
- A change currently in review tints the live question clock by the same ladder: green before the easy point, amber between, red from the hard point. If it ships, the grading rule becomes visible *while the learner is answering*, which we expect to change behaviour (see 6.6).

---

## 4. What we are trying to achieve

**Primary objective.** The automatic grade should carry the same meaning a careful human self-assessor would give it: Easy means "I knew this immediately", Good means "I retrieved it with normal effort", Hard means "I had to work it out or I nearly lost it". It should hold across learners, card lengths, question formats and accessibility needs.

**Secondary objectives**, in priority order:

1. **Fairness.** A slow reader, a non-native speaker, a learner using large text or a screen reader, or someone with a motor impairment should not be systematically labelled Hard because of pace alone. The current rule's fixed reading speed has no accommodation for this.
2. **Scheduling honesty.** The grade mix must reflect real recall, so intervals stay calibrated. If the rule labels too many answers Easy, the schedule over-promises; if too many Hard, the workload balloons.
3. **Stability.** The rule should not swing between sessions or reward a single fast or slow answer with a different standard. A learner should be able to form an intuition for what Easy means.
4. **Explainability.** The learner should be able to understand, in one sentence, why an answer was graded a particular way.
5. **Data economy.** Everything must run offline, on-device, cheaply, from the first day of use, and without new infrastructure.
6. **No retroactive change.** A rule change must never re-label past reviews or silently rewrite the schedule history.

**Non-goals.** Predicting exam scores; replacing FSRS's memory model; measuring reading speed for its own sake; social comparison between learners.

---

## 5. The variables we have

All of the following are stored on the device per learner. Sample sizes vary from a few dozen to a few thousand reviews per learner, depending on use.

### 5.1 Per review (the core dataset)

| Variable | Notes |
|---|---|
| Rating (Again / Hard / Good / Easy) | Stored as 1–4. In classic mode it is the learner's own label; in simplified mode it is the app's proposal, possibly edited. |
| Timestamp | Wall clock, millisecond resolution. |
| Dwell seconds | Whole seconds; empty (null) when not timed; 0 means untimed rather than instant. |
| Card state before the review | New, Learning, Review or Relearning. |
| Correctness at answer time | True/false, empty for reviews recorded before this field existed. |
| Declared guess | True/false, empty for older rows. In classic mode it is always "not a guess". |
| Stability after the review | Days, FSRS estimate. |
| Difficulty after the review | 1–10, FSRS estimate. |
| Scheduled interval after the review | Whole days. |

### 5.2 Per card (memory state, current values)

| Variable | Notes |
|---|---|
| State | New, Learning, Review, Relearning. |
| Repetitions, lapses, leech flag | Counters; a leech is a card failed repeatedly. |
| Stability | Days until recall is expected to fall to the target retention. |
| Difficulty | 1–10. **Careful:** this is an output of the grading loop, not an independent input (see 6.7). |
| Due date, last reviewed | Timing of the schedule. |
| Predicted recall at review time | Derivable from stability and elapsed days; not stored. |

### 5.3 Per question (content)

| Variable | Notes |
|---|---|
| Stem, options, passage, explanation | Stored as structured elements: text, image, table, mathematical formula. Character counts and element counts are available. |
| Number of options, number of correct options | Single- and multi-correct questions both exist. |
| Marks | A mark value per question (default 1, commonly 1 or 2), used for exam scoring. |
| Difficulty label and tags | Imported metadata, of varying quality; may be absent. |
| Category and paper | Grouping used for scope, mastery and blueprints. |
| Images, tables, formulas | Counts available; parsing cost is not otherwise measured. |

### 5.4 Test-mode data (a second, independent sample)

Mock tests also time each question, with the same dwell semantics but a different context: no explanation, formal timing, sometimes a countdown. Each answered question records dwell, correctness and the selection. From these the app already derives:

- per-question median dwell and per-question attempt/correct/wrong/skipped counts;
- per-sitting median dwell;
- report rules: "rushed" = below the larger of 5 seconds and 25% of the sitting median; "slow wrong" = at or above the larger of 15 seconds and the sitting median; the review screen's "Slow" filter = at or above the larger of 60 seconds and twice the median — all applied only when a sitting has at least ten timed answers;
- a practice-set builder called "Slowest" that keeps questions whose own median dwell is at or above the 75th percentile of per-question medians (floor 30 seconds).

These are useful precedents: in its reports, the product already accepts **learner-relative** pace thresholds. The auto-grader is the one place that does not.

### 5.5 What we do not record

No per-option timing, no tap or keystroke stream, no scroll events, no per-segment clock pauses (only the banked total), no reading-speed calibration, no device or screen-size metadata, no whether the answer was changed before Check, no time-of-day normalisation beyond the timestamp, no language or reading-comfort flag, no self-rated confidence separate from the grade.

---

## 6. Why the current rule is probably not good enough

**6.1 Time conflates several processes.** Reading, retrieval, deliberation, elimination of distractors, hesitation, mis-taps and motor time all land in one number. The grade we want is about retrieval, which is only part of it.

**6.2 The reading model is coarse and slightly optimistic.** Twenty characters per second is average prose speed; exam stems with near-miss distractors are read more slowly. Options are counted once, although learners typically re-read them while eliminating. Tables and formulas are charged only for whatever characters they carry, although parsing them can take many seconds.

**6.3 The two constants are absolute.** A learner who reads at 150 words per minute pays the same 8 seconds of thinking budget as one who reads at 250. The predictable result is a systematic bias: slower readers accumulate Hard grades, faster readers accumulate Easy grades, and the scheduler treats the difference as memory. Accessibility settings, which make text larger and reading slower, push the same way.

**6.4 Card condition is ignored.** A first exposure requires encoding, not retrieval; a third review of a well-known card should be fast. The same ladder applies to both. Leeches and previously lapsed cards are also genuinely harder, and being slow on them is not the same signal as being slow on a fresh easy card.

**6.5 The Good band is a constant 22 seconds.** On a long, dense question that is a narrow slice; on a short factual one it is most of the useful range. A relative band would behave better.

**6.6 The rule may become visible while answering.** If the coloured clock ships, the measure becomes a target. Learners can rush to stay green, or stall to justify a Good grade. Independently of the display, there is a manual pause control: pausing removes time from both the display and the grade, so the cheapest way to avoid a Hard grade is to stop the clock. Both are measurement-validity problems, the first created by the display and the second already present in the timing itself.

**6.7 The grade and the memory model are mutually dependent.** Difficulty is updated by the grade, and the grade is produced by the threshold. If a future threshold uses difficulty as an input, slow answers raise difficulty, which widens the threshold, which makes the same pace count as faster next time. The loop can drift until the grade means nothing. Any proposal that conditions on FSRS outputs must show how it avoids this.

**6.8 We already have the data to do better and are not using it.** Classic-mode reviews store both a human label and the dwell that produced it. That is a labelled sample of this learner's own notion of Easy, Good and Hard, and it can be used to fit the automatic rule without circularity, because the labels never depend on the thresholds.

---

## 7. Candidate directions to evaluate

These are options, not commitments. Each needs the research team's judgement.

| Direction | Idea | Data needed | Main risk |
|---|---|---|---|
| A. Better reading model | Charge options a re-read factor, weight tables and formulas, add a fixed per-card cost; possibly learn the learner's own reading speed from dwell on the simplest cards. | Content shape (have) | Overfitting a small sample |
| B. Learner-relative pace from self-grades | Learn the Easy and Hard thinking budgets from classic-mode labels: the median thinking time of answers the learner called Easy becomes the easy budget, and likewise for Hard. Blend with the default when the sample is small. | Classic-mode reviews with dwell (have) | Self-report bias; needs enough labelled reviews |
| C. Per-card history | For a repeat card, use that card's own median thinking time, blended with the learner-level estimate in proportion to how many times it has been seen. | Per-question review history (have) | Sparse; a memorised card can look artificially fast |
| D. Card-state conditioning | Multiply the thinking budget by a bounded factor for new, learning, lapsed and leech cards. | Card state (have) | Choosing the factors; must stay bounded |
| E. Outcome calibration | Fit a probability-of-correct curve against dwell; place Easy where the curve is near its ceiling and Hard where extra time stops buying accuracy. Fits what the grade is supposed to mean, rather than assuming a pace. | Correctness plus dwell across many reviews (have) | Data-hungry; needs a defensible definition of "stops buying accuracy" |
| F. Format weights | Add per-option, per-table, per-formula and per-mark costs to the reading model. | Content shape (have) | Arbitrary constants; needs measurement |
| G. Simpler grading | Drop the Easy band and use one "slow" threshold, or drop automatic grading altogether and use time only as feedback. | None | Loses information; changes product behaviour |
| H. Session-relative percentile | Set the budgets from the learner's own median thinking time in recent sessions, mirroring the test-report rules. | Recent sessions (have) | Reactive; would drift and make the clock change meaning mid-session |

Two observations that cut across all of them:

- **Anchoring matters.** Setting thresholds to force a fixed share of Easy grades (for example "the fastest 30% are Easy") would change the grade mix regardless of learning and inflate intervals. The target distribution should come from the learner's own labels, not from a quota.
- **Only some inputs are safe.** Content shape and card state are independent of the grade. Dwell history is affected by the thresholds but only through the learner's behaviour, which is the thing we are trying to model. FSRS difficulty and predicted recall are direct outputs of the grade and should be treated as unsafe inputs unless a proposal shows otherwise.

---

## 8. Questions for the research team

**Validity**

1. Is dwell a defensible proxy for retrieval difficulty in multiple-choice study, and what does the literature on response time and self-paced study say about its limits?
2. How should reading time be separated from retrieval time when only whole seconds and a single total are available?
3. Does the answer differ for first exposure (encoding) versus review (retrieval)? Should new cards use a different rule entirely?

**Modelling**

4. Which functional form is most defensible: additive reading plus thinking, a multiplier on a baseline, quantiles of the learner's own distribution, or a survival/probability model?
5. Should the thresholds be global, per learner, per category, per card, or a hierarchy with shrinkage? What pooling structure and what minimum sample sizes?
6. How should the rule handle the deliberate pause control, which currently lets a learner remove time from both the display and the grade?
7. Is it acceptable to learn the automatic rule from self-grades, and how should self-report bias (over- and under-confidence, unfamiliarity with the scale) be corrected?
8. How should the rule avoid the feedback loop with FSRS difficulty while still accounting for cards that are genuinely hard?

**Fairness and accessibility**

9. What accommodation, if any, should exist for slow readers, non-native speakers, large-text users and assistive-technology users? Is a per-learner pace calibration the right answer, and what does it cost in explanation and trust?
10. Should response-time grading be opt-in rather than the default, given its accessibility risks?

**Data and evaluation**

11. What is the minimum labelled sample for a per-learner estimate to be better than the default, and how should confidence be communicated (or hidden) from the learner?
12. How should we evaluate a proposal offline using the stored logs: agreement with self-grades, calibration against correctness, fairness across content length and learner pace, stability over time, and simulated scheduling impact?
13. How should we detect drift, for example a learner becoming faster as they master a subject, without chasing noise?

**Presentation**

14. Is showing the grade band live (the coloured clock) defensible, or does it corrupt the measurement it displays? Is post-answer feedback the better design?
15. How should a derived threshold be explained in one sentence to the learner?

---

## 9. How we would judge a proposal

Offline replay of existing review logs, with no new data collection required:

- **Agreement with self-grades.** Exact-match rate and quadratic-weighted agreement between the proposed automatic grade and the learner's own classic-mode grade on the same reviews; a confusion matrix, not just accuracy, because the cost of Easy-versus-Good is not the same as Good-versus-Hard.
- **Calibration against correctness.** The proportion of correct answers should rise from the Hard band to the Good band to the Easy band, with a useful margin. A rule that produces bands with no correctness difference has no evidential value, whatever its agreement with self-report.
- **Fairness.** Disagreement should not correlate with content length, category, or the learner's own median pace. A rule that is right on average but systematically wrong for slow readers is not acceptable.
- **Stability.** How much the derived thresholds move between sessions; whether a single answer can move them.
- **Scheduling impact.** Simulate the grade mix under the new rule, then the resulting intervals, daily workload and leech rate. A rule that changes intervals by tens of percent needs a deliberate decision, not a side effect.
- **Explainability.** Whether the rule can be stated in one sentence and whether the effective thresholds can be shown to the learner.

Where possible, a small within-app experiment (for example, colour on versus off) would tell us whether the display changes dwell, but privacy and the offline design make this a product-level decision rather than a research default.

---

## 10. Constraints any proposal must satisfy

1. Runs on-device, offline, with no server and no new data collection.
2. Cheap: no additional database query per card; computation at most once per session.
3. Deterministic: the same inputs give the same grade.
4. Whole seconds, bounded, and monotone — a longer card never gets a shorter allowance than a shorter one.
5. A minimum Good-band width, so the three bands cannot collapse.
6. Works with zero history using a documented prior, and improves as history accumulates.
7. Frozen for the duration of a session; a card on screen never changes its thresholds.
8. Never re-labels or rewrites past reviews.
9. The effective thresholds can be shown to the learner in the settings screen.
10. No dependency on the outputs of the grader itself (see 6.7).

---

## 11. Risks and failure modes

- **Feedback drift** between thresholds and the memory model.
- **Gaming** through the pause control, or by answering before reading.
- **Self-report bias** in the label source, including unfamiliarity with the four-point scale.
- **Survivorship**: cards that are abandoned mid-question, or questions the learner avoids, are missing from the sample.
- **Sparse data**: many learners will have few labelled reviews, and per-card estimates need several observations.
- **Non-stationarity**: a learner genuinely gets faster as they master a subject, which looks like drift.
- **Overfitting one learner's habits** — the same person on a different device or in a different mood.
- **Accessibility regression**: a stricter rule penalises exactly the users least able to answer quickly.
- **False precision**: a threshold derived from thirty reviews is not a measurement.

---

## 12. Glossary

- **Card** — one question as scheduled by the spaced-repetition system.
- **Dwell** — time the question card is visible and unanswered, to whole seconds, excluding explanation reading and pauses.
- **Ladder** — the two dwell points that separate the three automatic bands.
- **Band** — Easy, Good or Hard, as implied by dwell.
- **Reading allowance** — the seconds added to both ends of the ladder for the text and images in the card.
- **Stability** — FSRS estimate of how many days until recall probability falls to the target.
- **Difficulty** — FSRS estimate, 1–10, of how hard the item is for this learner.
- **Retrievability** — the predicted probability of recall at a given moment.
- **Leech** — a card failed repeatedly and flagged for extra attention.
- **Rating** — the 1–4 value written to the schedule: Again, Hard, Good, Easy.

---

## 13. Appendix: current defaults at a glance

| Setting | Default | Range | Meaning |
|---|---|---|---|
| Minimum easy answer threshold | 8 seconds | 1–3600 | Thinking budget for Easy, before the reading allowance |
| Hard answer threshold | 30 seconds | 1–3600, not below the easy value | Thinking budget for Hard, before the reading allowance |
| Reading speed assumed | 20 characters/second | fixed | About 197 words per minute |
| Reading allowance per image | 3 seconds | fixed | Stem, options and passage each count |
| Untimed review (0 seconds) | Good | — | Treated as unknown, not fast |
| Wrong answer, empty answer, declared guess | Again | — | Bypasses the ladder entirely |
| Target retention (context) | 0.90 | 0.75–0.97 | Not part of the thresholds; included because it shapes intervals |

Questions, corrections and proposed formulas are welcome in any format. The two things we would find most useful are (a) a defensible rule or rule family, and (b) an evaluation plan we can run on the stored logs before changing anything.
