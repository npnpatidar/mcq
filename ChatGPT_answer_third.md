# Third pass: consolidate the design without overstating what the data can prove

**Responding to:** `chatgpt_secondpass.md`, which revises the interruption policy and raises additional implementation, calibration and product-governance questions.

## Executive answer

The second pass materially improves the proposal. It corrects the claim that manual fallback is incentive-neutral, restores “count everything” as an explicit design option, distinguishes low-leverage deferral from neutrality, and identifies several implementation details that would otherwise invalidate the measurement.

I agree with its central conclusion: **manual fallback is a deliberate trade-off, not a gaming-proof solution.** I also agree that the measurement change should not be held hostage to a future calibration sample, and that the live coloured clock is a product decision whose behavioural risk should be measured rather than presented as a research veto.

I would make five refinements before treating the proposal as implementation-ready:

1. Do not describe the invalid-rate tripwire as detecting gaming. It detects a pattern consistent with leverage; genuine interruptions can produce the same pattern.
2. Do not set a numerical invalid-rate bound until a baseline or pilot establishes what ordinary interruption rates look like. Pre-commit the *method and response*, not an invented percentage.
3. Preserve the distinction between elapsed dwell for grading and active question time for session summaries.
4. Treat the proposed learner-specific calibration as an optional, dormant-capable feature, not a required destination of the measurement change. A guided calibration path is needed if activation matters.
5. Revisit the first pass's proposed boundary estimator. Median midpoints are a useful baseline, but they are not automatically the best classifier for a learner's four-point scale, especially with sparse or skewed labels.

The rest of this document separates accepted corrections, remaining decisions, and a concrete staged plan.

---

## 1. The decision is a trade-off, not a search for a perfect fallback

The second pass's four-outcome table is the right framing:

| Policy for a long interruption | Main benefit | Main cost / risk |
|---|---|---|
| Automatic Good | Smooth session flow | Rewards slow or interrupted attempts with a better rating |
| Manual grade | Fair to genuine interruptions; preserves a scheduler update | Gives the learner a per-card route to self-rating |
| Defer / no scheduler write | Avoids a time-derived rating and avoids per-card self-rating | Changes review counts and timing; may be advantageous for some cards and creates extra work |
| Count all elapsed time and always grade | No alternate path to obtain a different rating | Long interruptions contaminate the response-time signal and can create false Hard grades |

Two points should be explicit.

**First, no policy is both perfectly fair and impossible to exploit.** Counting every second removes the choice of fallback, but treats an interruption as if it were retrieval effort. Manual grading avoids that false time judgement, but reintroduces self-assessment for that card. Deferral avoids writing a possibly misleading rating, but it is not cost-free: it changes when the card is seen again and may affect counters or leech status depending on scheduler semantics.

**Second, “rating leverage” is not the only kind of leverage.** Deferral has no immediate rating, but it can preserve a stability estimate, avoid a lapse, or prevent a leech counter from advancing. Those consequences need to be verified against the actual FSRS integration rather than assumed from the label “no scheduler write”. The second pass correctly flags this; implementation analysis should enumerate exactly which card fields and counters change on defer.

### Recommendation

Retain **manual grading as the initial interrupted-card policy**, but state plainly that it is a return to self-assessment for that card. It is the least disruptive option if the product already accepts Classic-mode self-ratings. Do not justify it as incentive-neutral.

Pair it with monitoring and a pre-agreed escalation process. If the product's overriding requirement is that a learner must not be able to change an automatic Hard into a self-selected Good by triggering an interruption, choose deferral instead. That is a product integrity choice, not a statistical conclusion.

---

## 2. Make the tripwire measurable, but do not invent its threshold

The second pass recommends recording the time already elapsed when an interruption begins and looking for invalid attempts clustering near the Easy or Hard boundaries. This is useful instrumentation.

I would record at least:

- elapsed time at first away transition;
- elapsed time at each subsequent away transition, or enough segment information to derive it;
- cumulative away duration;
- number of away transitions;
- invalidation reason;
- eventual fallback rating and its source;
- the automatic grade that would have been proposed from elapsed time, retained for analysis only and never shown as the actual grade for an invalid attempt.

The last field is important: it allows a direct comparison between the grade the ladder would have proposed and the grade selected through manual fallback. It should be clearly marked as a counterfactual analytic field, not a scheduler rating.

### Do not pre-commit an arbitrary percentage

A rule such as “if more than 5% of reviews are invalid, switch policy” sounds rigorous but has no basis in the supplied data. The acceptable rate depends on how away events are detected, how the app is used, and how many reviews a learner completes.

Instead, pre-commit a *decision procedure*:

1. Collect a pilot under the new timing semantics.
2. Estimate the ordinary invalidation rate and its uncertainty, overall and per learner.
3. Examine whether invalid attempts disproportionately occur after the elapsed time approaches a grade boundary.
4. Compare fallback ratings with the counterfactual automatic grades and with ordinary Classic-mode ratings.
5. Set a threshold for escalation using the pilot distribution and an explicit tolerance for friction and rating leverage.
6. Reassess at a fixed review cadence, not after every isolated event.

The signal is not proof of intent. A learner may be interrupted more often during long questions or at inconvenient moments. Report boundary clustering as **evidence of possible rating leverage**, not as a classifier of cheating.

### A useful comparison

For invalid attempts, report:

- manual fallback rating distribution;
- counterfactual automatic-grade distribution;
- difference between the two distributions;
- share of invalid attempts whose counterfactual grade was Hard but whose manual grade was Good/Easy;
- the same comparison for attempts interrupted before the Easy boundary.

This gives a clearer measure of how much the fallback changes the rating path than invalid rate alone.

---

## 3. Measurement specification: adopt the corrections

The second pass's measurement specification is a substantial improvement. I recommend the following record:

| Field | Meaning |
|---|---|
| `elapsed_seconds` | Monotonic elapsed time from answerable card display to answer commit |
| `away_seconds` | Cumulative duration when the card was not available for answering |
| `away_event_count` | Number of away transitions |
| `elapsed_at_first_away` | Elapsed time at the first away transition |
| `timing_valid` | Whether elapsed time is eligible for automatic grading |
| `invalidation_reason` | E.g. long absence, process loss, clock state unavailable |
| `timing_rule_version` | Version of the timing semantics |
| `rating_source` | Human-selected, automatic, or human-edited |
| `counterfactual_auto_rating` | Analytic-only result for invalid attempts; not written to FSRS |

### Monotonic clock and process death

According to the second-pass source, the app already uses a monotonic elapsed-time source for both card and sitting clocks. Treat that as an existing property to preserve, not a new feature to build.

The source also says that if the app process is killed, the attempt is lost rather than reconstructed. Preserve that conservative behaviour. Do not use wall-clock subtraction to reconstruct a duration after process death.

### Defining away correctly

Do not equate every lifecycle pause with a genuine absence. Transient system dialogs, permission prompts, split-screen changes and configuration changes can produce lifecycle events without making the question unavailable.

Use a deliberate state definition based on window focus and actual visibility, with a short debounce. Count screen-off/device lock as away where detectable. Do not count in-app navigation if the question remains visible and answerable.

The exact debounce duration is not specified by the source and should be selected through implementation tests, not guessed here. Test at least: permission dialog, notification shade, split-screen, rotation/configuration change, screen lock, app switch, and process death.

### Cumulative tolerance

The allowance must be cumulative over the attempt. A 20-second allowance per event would permit unlimited hidden time through repeated short absences.

The 20-second value remains a provisional pilot value, not a validated research constant. Compare 10, 20 and 40 seconds and report invalidation rates and user friction. Keep this tolerance independent of card length and of the Easy/Hard thinking budgets.

---

## 4. Keep grading dwell separate from session accounting

The second pass's proposed split is the cleanest resolution of the summary arithmetic issue:

- **Dwell for grading:** elapsed time, including short away periods; long away periods invalidate automatic grading.
- **Active question time for session summaries:** time while the app is actively presenting the question and the learner can answer.

These quantities answer different questions. A call during an attempt may count toward the attempt's elapsed dwell, but it is not active study time in the session summary.

Do not derive explanation time by subtracting elapsed dwell from an active sitting total. Keep the session accounting based on active durations, and keep the grading input based on elapsed duration. Label the two quantities clearly in code and UI so they are not accidentally interchanged.

---

## 5. Correct the calibration mathematics before fitting anything

The second pass identifies three important issues: rounding direction, clipping negative residuals, and selection caused by excluding Again. I agree with all three.

### 5.1 Keep a signed residual for analysis

If the model estimates reading time and subtracts it from dwell, preserve the signed residual:

\[
r = t_{\text{elapsed}} - t_{\text{reading}}
\]

Do not floor negative values at zero before estimating medians or fitting boundaries. A negative residual may reflect an imperfect reading model, fast recognition, or measurement noise; collapsing all such values to zero destroys information and distorts the fast tail.

If the production rule requires non-negative thinking time, apply clipping only at the final operational boundary, not during model fitting.

### 5.2 Use one rounding convention

The current rule rounds the reading allowance upward and stores dwell in whole seconds. That can systematically reduce the residual. Prefer retaining finer precision internally if available, and round only when displaying seconds or when a whole-second scheduler threshold is required.

If the stored historical dwell is already whole seconds, acknowledge that limitation in replay. Do not pretend a new precision convention can be retroactively applied.

### 5.3 Be explicit about the Again selection

Excluding wrong, empty and declared-guess attempts from the Easy/Good/Hard time-boundary fit is sensible because the product already forces those cases to Again. But the fitted time distributions then describe only correct, non-guess, timed responses with usable self-grades.

Report the number and proportion excluded. A learner with many failures may have a very different retained sample from a learner who answers most questions correctly. The model should not be described as representing all attempts.

### 5.4 Median midpoints are a baseline, not the final answer

The first-pass approach of taking the median residual for each human grade and placing boundaries at midpoints is interpretable. But it implicitly treats the three grade classes as if they had comparable priors and equal misclassification costs. It may produce poor boundaries when one grade is rare or when the learner's labels are skewed.

Compare at least:

- current fixed thresholds;
- current thresholds with one global scale adjustment;
- learner-specific median-midpoint boundaries with shrinkage;
- boundaries chosen to optimise a pre-specified weighted agreement metric, with regularisation and minimum sample requirements.

Use held-out or temporally later reviews where the sample permits. With a few dozen observations, results will be noisy; show uncertainty and avoid reporting thresholds with false precision.

---

## 6. The n=1 problem changes the product plan

The second pass is right: Classic is not the default mode, so a large post-change Classic-labelled sample may never accumulate. This is not a minor statistical caveat; it determines whether the calibration feature will ever activate.

Separate the work into two independent deliverables:

### Deliverable A — measurement correction

Ship pause removal, away-time instrumentation, invalidation and the timing-version field on their own merits. These changes improve measurement integrity without requiring a fitted learner-specific threshold.

### Deliverable B — optional calibration

Keep calibration behind a setting or feature flag. Use the current constants as the documented fallback. Activate only when the learner has enough eligible labels and the fit passes pre-defined quality checks.

If calibration is an important product goal, provide a deliberate guided Classic calibration session or periodic prompt. Make it clear that asking a learner to self-grade is a change in product behaviour and may influence their ratings.

If the product does not want to add that flow, “calibration remains dormant for learners without sufficient Classic data” is an acceptable outcome. Do not quietly substitute Simplified-mode proposals as labels; that would train the model on its own output.

### Single-learner evaluation

The supplied product description says each installation stores one learner's history and has no server or cross-user dataset. The realistic analysis is therefore a within-learner replay on a user-initiated backup export, not a population study.

The backup is described as containing review dwell, correctness, guess flag, pre-review state, post-review stability/difficulty/interval and question content. That is enough for much of the offline replay. It does not recover historical away time or historical manual-pause segments. Keep those cohorts separate.

---

## 7. New cards need a separate policy decision

The second pass adds that new cards should be Again or Good only, rather than automatically allowing Easy and Hard. This is a plausible product policy, but the supplied material does not establish that it is universally correct.

A first exposure is encoding rather than retrieval, so its response time should not be interpreted using the same construct as a review. However, the original product description says FSRS receives one of four ratings and uses the rating to update stability and difficulty. Forcing Good on every first exposure is not neutral: it fixes the initial scheduler input and may affect initial stability and difficulty.

I would therefore not hard-code “new = Again or Good” without a separate scheduler simulation. Compare:

1. current four-grade behaviour for new cards;
2. a restricted Again/Good policy;
3. a separate first-exposure handling path, if the scheduler integration supports it.

Report initial stability, difficulty, first interval, subsequent workload and lapse outcomes. Pre-register what magnitude of interval compression or workload change would be acceptable.

The key conclusion is narrower: **do not use first-exposure dwell to calibrate retrieval thresholds as though it were a review.** Whether new cards can receive Easy or Hard is a separate product/scheduler decision.

---

## 8. The live coloured clock: keep the owner decision, fix the inconsistency

I accept the second pass's correction. Research can identify a predictable measurement-reactivity risk; it cannot unilaterally veto an explicit product requirement.

The appropriate recommendation is:

- The coloured clock may ship as an owner-decided display layer.
- Its behavioural risk should be documented and evaluated separately.
- The band must not enter calibration labels or threshold fitting.
- The display must not be described as a validated indicator of memory strength.
- Fix the zero-second inconsistency before release.

At zero, the grader currently treats dwell as untimed and proposes Good, while the display paints the fastest band. The least disruptive fix is to withhold the tint until the first measured second has elapsed. This changes neither the untimed grading rule nor the scheduler.

A personal on/off comparison across sessions can be informative, but it is a single-learner observation. It cannot establish a general causal effect. If a controlled comparison is feasible, keep it separate from the threshold-calibration dataset and do not change other timing rules during the comparison.

---

## 9. Revised implementation and evaluation sequence

I recommend this sequence:

### Phase 1 — measurement foundation

- Remove the manual pause control.
- Preserve the existing monotonic clock.
- Define away using focus and actual visibility, with debounce.
- Accumulate away time across the attempt.
- Add elapsed time, away time, event count, validity, reason, timing version and rating source.
- Preserve the existing process-death behaviour: lose the attempt rather than reconstruct it.
- Keep active question time separate from elapsed dwell.
- Resolve the invalid-attempt policy explicitly: manual grade initially, with the trade-off stated.

### Phase 2 — pilot and policy monitoring

- Test 10-, 20- and 40-second cumulative tolerances.
- Record invalidation rate, away-event patterns, fallback grades and counterfactual automatic grades.
- Examine boundary clustering as a warning signal, not proof of gaming.
- Set a data-informed escalation bound after observing a pilot.
- If the fallback creates material rating leverage, consider switching to deferral.

### Phase 3 — calibration readiness

- Use only post-change, valid, uninterrupted Classic-mode reviews as the primary labels.
- Keep human-selected, automatic and edited ratings distinguishable.
- Exclude invalid rows and Again outcomes from the primary time-boundary fit; report their counts.
- Preserve signed residuals and use consistent precision.
- Compare median-midpoint, weighted-agreement and baseline models.
- Use shrinkage to the existing prior when sample size is small.
- Keep calibration dormant when evidence is insufficient.

### Phase 4 — scheduler replay

- Simulate grade distributions and resulting FSRS intervals.
- Compare daily workload, stability, difficulty, lapses and leech rates.
- Include new-card policy variants separately.
- Do not rewrite past review records or historical scheduler state.
- Version the new rule and apply it only to future reviews.

### Phase 5 — display evaluation

- Treat the coloured clock as a separate product intervention.
- Fix the zero-second display mismatch.
- Compare on/off behaviour only after the measurement change is stable.
- Do not infer population effects from one learner's sessions.

---

## 10. Answers to the five open questions

1. **Is a pre-committed invalid-rate bound plus boundary-clustering test the right tripwire?** Yes as a framework, but not with an arbitrary percentage. Add the fallback-versus-counterfactual grade comparison. Establish the numerical bound from a pilot and report uncertainty.

2. **Permanent escalation or per-release review?** Pre-commit the trigger and the response, but review the policy on a fixed release cadence. A permanent automatic switch based on one noisy learner-level rate could overreact. A documented trigger with a release-level decision is more robust.

3. **Is the choice between rating risk and measurement risk?** Yes, with a qualification: deferral adds a scheduling/workload cost, and manual grading adds a self-report risk. The trade-off has at least three dimensions: measurement validity, rating integrity and interruption fairness.

4. **Is a clock-hidden Classic sitting acceptable for calibration?** It is a useful way to reduce anchoring from a visible dwell number, but it changes the conditions under which the learner grades. Treat it as a deliberate calibration protocol and record that protocol. Compare a small number of hidden-clock and ordinary Classic labels if feasible; do not assume either is an unbiased ground truth.

5. **If the sample never materialises, is dormant calibration acceptable?** Yes. Measurement improvements still stand on their own. If learner-specific calibration is a product objective, add a guided calibration flow; otherwise keep the existing constants as the documented prior and do not manufacture labels from the automatic system.

---

## 11. Final recommendation

The second pass is substantially correct and should replace the earlier framing. The final design should say:

> **Measure elapsed dwell with a monotonic clock; count short absences; invalidate long interruptions; and never turn invalid timing directly into an automatic favourable rating. For invalid attempts, use manual self-assessment as an explicit, monitored trade-off, with a pre-defined escalation path if the fallback creates material rating leverage.**

Then keep the work properly separated:

- measurement correctness does not wait for calibration;
- calibration uses only independent, valid human labels;
- the scheduler is evaluated by replay before thresholds change;
- the coloured clock remains an owner decision with a documented behavioural risk;
- new-card handling is evaluated separately from retrieval-time calibration;
- historical reviews are never silently re-labelled.

This is a defensible design because it makes the remaining compromises visible, measurable and reversible. It does not claim that time reveals memory perfectly, that manual fallback cannot be gamed, or that a small single-learner dataset can validate a universal rule.
