# Second pass: review of the interruption proposal

**Responding to:** `chatgpt_firstpass.md`, which proposes removing the manual pause, counting wall time, applying a short absence allowance, and routing interrupted reviews to manual grading.

## Executive assessment

The first pass identifies a real weakness in the current design: removing the manual pause while continuing to exclude background and scroll-away time would merely move the same incentive to other controls. It is also right that a long interruption should not automatically become **Hard**, and that an invalid timing record should not silently become **Good**.

I agree with the direction, but not with the claim that the proposed fallback is incentive-proof. The most important unresolved issue is this:

> **If crossing the interruption limit lets a learner replace an automatic time grade with a self-selected grade, deliberately triggering an interruption may still be advantageous.**

That is the central design question to resolve before implementation. The first pass correctly rejects “invalid → Good”, but “invalid → manual grade” is not automatically neutral: it changes who chooses the rating, and the learner may choose Easy when the automatic ladder would have said Hard.

My recommendation is therefore a **two-part design**:

1. Remove the discretionary pause and stop excluding ordinary background/scroll time from the measured elapsed time.
2. Treat long interruptions as *ungradable by time*, but do not describe manual grading as an anti-gaming solution. Either accept it explicitly as a return to self-assessment, or use a non-scheduling/deferred outcome if preventing a route to a more favourable rating is a hard requirement.

The product should make that policy choice explicitly rather than imply that one fallback is both perfectly fair and impossible to game.

---

## 1. What the first pass gets right

### 1.1 Removing the manual pause is a sound change

In Simplified mode, the manual pause lets a learner stop the measured clock without answering. That is a direct way to change the proposed grade while leaving the answer unchanged. Removing the control closes that particular route and simplifies the explanation of the clock.

This does not affect Classic mode in the same way, because the learner supplies the grade there.

### 1.2 Active-time-only is not a complete fix

If backgrounding or scrolling away still stops the clock, then removing the visible pause button does not remove the underlying opportunity to stop time. The first pass is right to distinguish the manual pause from lifecycle and visibility pauses.

### 1.3 Long interruptions should not be translated into a memory judgement

A phone call or app interruption is not evidence that retrieval was difficult. Counting a multi-minute absence as ordinary response time would contaminate the timing signal. A long-interruption state is preferable to automatically assigning Hard.

### 1.4 Historical timing data needs a measurement-version boundary

The old records do not contain pause segments or away-time totals. They cannot be reliably converted into the proposed new elapsed-time measure. New calibration should therefore use only records collected under the new, documented timing definition. Old data may still be useful for exploratory analyses, but it should not be mixed into the same threshold fit as if the measurements were equivalent.

---

## 2. The critical flaw: manual fallback can itself be an incentive

The first pass argues that manual grading is unattractive to stalling because it does not automatically award a better grade. That conclusion is too strong.

Consider a learner who expects the automatic ladder to produce Hard. If deliberately backgrounding the app long enough changes the path to four self-grading buttons, the learner has gained control over the rating. A learner who is inclined to over-rate their recall may choose Good or Easy. This may be more attractive than receiving Hard automatically.

This does not mean manual grading is inherently wrong. Classic mode already relies on self-assessment, and the product may reasonably decide that an interrupted review should fall back to that familiar mode. It means only that the fallback is **not incentive-neutral**.

The distinction is:

| Invalid-timing outcome | Fair to genuine interruptions? | Can it create a more favourable rating path? |
|---|---|---|
| Automatic Good | No reliable basis; may reward a difficult answer | Yes |
| Automatic Hard | No; treats interruption as memory difficulty | No, but unfairly punitive |
| Manual grade | Gives learner agency | Yes, if self-rating can be more favourable |
| Defer / no scheduler update | Avoids an unsupported rating | No immediate rating advantage, but adds friction |
| Ask learner to resume and then use elapsed time | May be acceptable for short interruptions | Depends on whether the interruption remains in the elapsed-time total |

**Decision:** if the requirement is “no interruption path may let the learner obtain a more favourable scheduler rating than the timing rule would otherwise produce”, manual grading does not satisfy it. If the requirement is “do not let the algorithm make a time judgement when the measurement is invalid”, manual grading is defensible, but the product must accept that it restores self-assessment for those cards.

I would not call either choice universally correct. It is a product policy trade-off.

---

## 3. Tighten the timing definition before choosing thresholds

The first pass uses “wall time” from card appearance to answer commit, including time in the background or while scrolled away. This is directionally clear, but implementation needs a precise definition.

I recommend recording these separate quantities for each timed attempt:

- `elapsed_seconds`: monotonic elapsed time from the moment the question becomes answerable until answer commit, including background and scroll-away periods.
- `away_seconds`: cumulative time during which the app is backgrounded or the question is not sufficiently visible, measured over the same attempt.
- `timing_valid`: whether `away_seconds` is within the configured interruption tolerance and the elapsed-time measurement is trustworthy.
- `timing_rule_version`: identifier for the timing semantics used to create the record.

The automatic ladder should use `elapsed_seconds` only when `timing_valid` is true.

### Important implementation detail: use a monotonic clock

Do not calculate elapsed time by subtracting wall-clock timestamps. The device clock can change because of network time correction, manual adjustment, time-zone changes or other system events. Use a monotonic elapsed-time source for durations. If the process is killed or the device reboots and the duration cannot be reconstructed reliably, mark the attempt as invalid rather than inventing a duration.

### Define “away” consistently

The app needs one consistent rule for when an attempt is away:

- App backgrounded or activity no longer resumed.
- Question card no longer sufficiently visible, if the interface can genuinely scroll it out of view.
- Screen locked or device otherwise unavailable, where detectable.

Do not count ordinary transitions within the app as away unless they actually prevent the learner from seeing and answering the question. Avoid relying on a fragile pixel-level visibility threshold if the UI can instead define a clear lifecycle/visibility state.

### Accumulate away time; do not reset the allowance

If the tolerance is 20 seconds, it should mean 20 cumulative seconds away during that attempt, not 20 seconds per interruption. Otherwise a learner could repeatedly leave for 19 seconds and accumulate arbitrarily large unobserved time.

---

## 4. Is a fixed 20-second absence allowance defensible?

The first pass sensibly proposes testing 10, 20 and 40 seconds, but it does not establish that 20 seconds is a validated value. It should be described as an engineering starting point, not a research-derived threshold.

I would keep the absence tolerance separate from the Easy/Hard thinking budgets. It serves a different purpose:

- Thinking thresholds estimate the pace associated with a grade.
- The absence tolerance decides whether the elapsed-time measurement is sufficiently interpretable to use at all.

Making the allowance a function of card length or the Hard threshold would mix these two concepts. A long question should receive a larger reading allowance in the grading model, but that does not automatically justify a longer period of unobserved time.

**Proposed initial policy:** use 20 cumulative seconds as a provisional tolerance, then report the proportion of attempts invalidated at 10, 20 and 40 seconds. Choose the value based on interruption frequency and fairness, not on whichever value produces the preferred grade distribution.

The brief does not provide empirical away-time distributions, so no stronger numerical recommendation is supported yet.

---

## 5. Recommended decision policy

I recommend the following behaviour, with one explicit product decision for invalid attempts.

| Situation | Timing treatment | Scheduler treatment |
|---|---|---|
| Correct, not declared a guess, valid timing | Use the automatic ladder | Write proposed rating; learner may edit if that remains part of the product |
| Wrong, empty, or declared guess | Time is irrelevant | Again |
| No timing available (legacy/imported/disabled) | Unknown | Preserve the existing documented untimed policy, currently Good, or use manual grading if product policy changes |
| Short absence within tolerance | Count it in elapsed time | Use ladder |
| Cumulative absence exceeds tolerance | Mark time invalid; do not use ladder | **Choose one policy below** |
| Classic mode | Time is not used to determine grade | Learner's own rating |

For the invalid row, choose deliberately:

### Option A — Manual grade (best continuity with Classic mode)

Show the four grades and a concise explanation: “This question was interrupted, so elapsed time cannot suggest a grade.”

Use this if the product values learner agency and accepts that interrupted attempts become self-assessed. Flag these rows and exclude them from automatic-threshold calibration. They may still be written to FSRS using the learner's selected rating, just as Classic-mode ratings are.

**Caveat:** this is not anti-gaming. A learner may intentionally trigger the fallback to avoid an automatic Hard. The product should monitor the frequency of invalid attempts and should not claim this route cannot improve a rating.

### Option B — Defer the scheduler update (stronger against rating manipulation)

Record the attempt and its interruption metadata, but do not write a new FSRS rating. Return the card to the queue or leave it due for another attempt.

Use this if preventing interruption-triggered access to a more favourable rating is more important than uninterrupted session flow. The UI should explain that no review grade was recorded and let the learner retry.

**Caveat:** genuine interruptions create extra work, and repeated interruptions can make the session frustrating. This is not automatically the fairest option.

I would not silently choose between A and B in implementation. It is a product decision that needs an explicit owner.

---

## 6. Calibration and evaluation changes

The first pass is right that the timing rule must be fixed before collecting Classic-mode calibration labels. I would make the evaluation plan more explicit.

### 6.1 Version and filter the data

For every review, retain:

- timing-rule version;
- elapsed seconds;
- cumulative away seconds;
- timing-valid flag and invalidation reason;
- study mode (Classic or Simplified);
- rating source (human-selected, automatic proposal, or edited proposal);
- correctness and declared-guess state, where available.

Only use valid, post-change Classic-mode reviews as the primary labelled dataset for fitting learner-specific time boundaries. Exclude invalid timing records from the fit. Do not treat Simplified-mode ratings as independent labels, because the proposal itself is derived from the timing rule.

Historical records without away-time data can be analysed separately, with an explicit caveat that their dwell definition differs.

### 6.2 Do not use all manual ratings indiscriminately

Classic-mode labels are the cleanest available target in the brief, but they are still self-reports. Keep rating source explicit. A manual grade produced only because a timing attempt was invalid is not necessarily equivalent to a normal Classic-mode grade: the interruption may affect the learner's judgement or willingness to continue.

For the primary calibration fit, I would use uninterrupted Classic reviews under the new timing definition. Run a secondary analysis including interrupted/manual-fallback reviews to quantify how much they change the fitted boundaries.

### 6.3 Evaluate the interruption policy itself

Alongside the existing threshold metrics, report:

- invalid-attempt rate at 10, 20 and 40 seconds;
- invalid-attempt rate by learner and session;
- proportion of invalid attempts that receive each manual grade, if Option A is chosen;
- whether invalid attempts cluster immediately before the automatic Hard boundary;
- whether a learner's invalid-attempt rate rises after the policy is introduced;
- completion, abandonment and retry rates for interrupted cards.

A spike in invalid attempts near the point where a card would become Hard is a warning signal, not proof of intentional gaming. Interpret it cautiously.

### 6.4 Separate policy effects from threshold effects

Do not change the timing definition, interruption policy, threshold-fitting method and live clock display in one release. Otherwise any change in grade mix or dwell behaviour will be difficult to attribute.

A sensible sequence is:

1. Ship the new measurement definition and metadata.
2. Keep the live clock visually neutral; do not show the Easy/Good/Hard boundary while answering.
3. Collect a sufficient post-change Classic-mode calibration sample.
4. Fit and replay candidate thresholds offline.
5. Compare scheduling simulations.
6. Roll out the threshold model separately, with a versioned rule and a rollback path.

---

## 7. The coloured clock should remain off by default

The first pass notes that removing the pause does not remove the live-colour incentive. I agree, and would make the recommendation stronger.

A live green/amber/red clock turns a measurement into a target. It can cause learners to rush, wait, or change their answer strategy to obtain a desired colour. That undermines the interpretation of dwell as a naturally occurring response-time signal.

For the initial implementation:

- Show elapsed time as a neutral readout, if showing it is useful.
- Do not show the current grade band or countdown to a boundary.
- Show the proposed grade only after the answer is committed.
- Keep any coloured-band display as an explicit optional experiment, not the default.

This isolates the effect of the new timing definition and reduces the chance that the interface changes the very behaviour being measured.

---

## 8. Accessibility and fairness

Removing the manual pause should not mean forcing every learner to be graded on speed. The first pass is right that an untimed/manual path should remain easy to find.

I recommend:

- Keep time-based grading optional, with a clear untimed mode.
- Do not infer a disability, reading speed or language ability from dwell.
- Do not use a learner's slower pace as a reason to label them Hard.
- Make the timing rule and its effective boundaries inspectable.
- Ensure screen-reader use, large text and other accessibility settings do not silently make the automatic grade stricter.
- Treat frequent interruptions as a reason to offer the untimed path, not as evidence of poor recall.

The current logs cannot identify why a learner is slow or interrupted. They cannot establish fairness for a particular accessibility group. Evaluation can test pace-related disparities among observed learners, but it cannot substitute for accessibility testing with affected users.

---

## 9. Answers to the five questions in the first pass

1. **Wall time or active time?** Prefer elapsed time that includes background/scroll-away periods, with a cumulative absence limit that invalidates long interruptions. Use a monotonic clock. Active time alone leaves the same incentive in place.

2. **Is 20 seconds right?** It is a reasonable provisional value to test, not a validated value. Compare 10, 20 and 40 seconds using invalidation rates, interruption patterns and user friction. Keep it independent of card length and thinking thresholds.

3. **Manual grade or defer?** Manual grading best preserves continuity and learner agency, but it is not incentive-proof. Deferral avoids allowing an interruption to unlock a potentially more favourable self-rating, but imposes extra work after genuine interruptions. The product must choose which objective has priority.

4. **What if manual fallback is frequent?** First inspect whether the cause is real lifecycle behaviour, a visibility implementation problem, or a low tolerance. Do not assume it means the learner needs untimed mode. Offer untimed mode when timing is consistently unsuitable, but diagnose the invalidation rate before attributing it to the learner.

5. **Does pause removal change the coloured-clock recommendation?** Yes, it makes a neutral display even more important. Removing the pause closes one behavioural route; visible boundaries still encourage rushing or strategic delay. Keep the live band off by default.

---

## 10. Final recommendation

Adopt the measurement change, but revise the first pass in three important ways:

1. **Define and record the measurement precisely:** monotonic elapsed seconds, cumulative away seconds, validity, invalidation reason and timing-rule version. The allowance is cumulative, not per interruption.
2. **Do not call manual fallback incentive-proof:** it is a reasonable self-assessment path, but it can let a learner replace an automatic Hard with a more favourable self-selected rating. Choose manual grading or deferral based on an explicit product priority.
3. **Keep the clock neutral:** no live grade colours during the answer. Evaluate the timing change separately from any future display experiment.

The resulting principle is:

> **Use elapsed time only when the attempt was continuously interpretable; count short absences, invalidate long interruptions, and never convert invalid timing automatically into a favourable scheduler grade.**

This preserves the useful insight in the first pass without claiming that the proposed manual fallback solves every incentive problem. It also gives the product a clean measurement foundation for the learner-relative threshold work described in the original research brief.
