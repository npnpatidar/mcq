# Second pass: the interruption fallback, the missing fourth option, and what is actually measured

**Responding to:** the review of the interruption proposal — specifically its central claim (§2), its decision table (§2 and §5), its measurement spec (§3), its evaluation plan (§6), and its recommendation on the live clock (§7).

**The review's position, in brief.** It accepts removing the discretionary pause, accepts counting background and scroll-away time, and accepts that a long interruption should not become Hard. It argues that routing an interrupted attempt to manual grading is not incentive-neutral, because a learner who expects Hard can earn the right to self-rate by waiting out the tolerance. It then offers three outcomes — automatic Good, manual grade, defer the scheduler write — and concludes that the product must choose between fairness and rating integrity rather than pretend one fallback achieves both. It also asks for a precise measurement record, a version boundary on historical data, and a neutral clock display. Most of that is right; this document takes issue with what the option set leaves out and with how far the clock recommendation travels.

**Short answer:** the correction is accepted. The first pass claimed that manual grading is "unattractive to stalling", and that is too strong: a learner who expects Hard can buy the right to self-rate by stepping away. What the first pass should have said, and what this document says, is that *no* fallback is both perfectly fair to genuine interruptions and impossible to game. The review's table, however, is incomplete in a way that changes the recommendation: it omits the only design with zero rating leverage, and it labels deferral as neutral when it is merely cheaper. Three context corrections also matter — the app already measures with a monotonic clock, "away" cannot be defined by lifecycle pause alone, and the coloured clock is an owner decision rather than a research finding.

---

## 1. The concession, stated precisely

The first pass argued that manual grading is "the only option that is both fair to interruptions and unattractive to stalling". The review's counter-example is valid: a learner who expects the automatic ladder to produce Hard can background the app past the tolerance, return, and self-select Good or Easy. Manual grading therefore does not close the incentive; it relocates and reprices it.

The correct description is **not incentive-proof** — which is not the same as **equally gameable**. The price matters, because it determines whether the exploit is worth taking:

| | Old exploit (manual pause) | New exploit (invalid timing to manual grade) |
|---|---|---|
| Cost | one tap | at least the full allowance of waiting, plus leaving and returning |
| What it buys | a grade computed on the banked time | a self-selected grade |
| What it forfeits | nothing | the automatic grade, and a clean timing record for that card |
| Who it appeals to | anyone who wants a better grade | someone willing to over-rate their own recall |

That is a deterrent, not a proof. The review is right that the document must stop implying otherwise. It should also stop implying the opposite — that because manual grading is imperfect, deferral is therefore the safe choice. Section 2 shows why that inference fails.

**The framing the document needs:** this is a choice between fairness to interruptions and integrity of the rating, and every option is a price on that axis. No option sits at the origin. That sentence should appear before the options, not after them.

---

## 2. The decision table is missing a row, and one label is wrong

The review's table has five rows, and two of them are not distinct policies. "Ask the learner to resume and then use elapsed time" is the count-everything policy with a prompt attached. "Automatic Hard" is the same policy with a discontinuity at the tolerance: time is counted, and the outcome is labelled Hard instead of being read off the ladder. So the table really offers three choices — automatic Good, manual grade, defer — plus one choice that appears twice and only ever in its punitive form.

That last one is the only design with zero rating leverage, and it deserves to be named as itself rather than dismissed in its punitive shell. The real spectrum is four:

| Invalid-timing outcome | Fair to genuine interruptions | Rating leverage | Where the leverage lands |
|---|---|---|---|
| Automatic Good | no | **high** — Hard becomes Good | every struggling answer |
| Manual grade (Option A) | yes | **medium** — Hard becomes whatever the learner picks | anyone willing to over-rate |
| Defer, no scheduler write (Option B) | yes | **low, not zero** | leech and lapse counters, review count |
| Count everything, invalidate nothing | **no** | **zero** | nowhere — there is no alternative path |

Three corrections follow.

**2.1 Deferral is not neutral.** It avoids a Hard rating, which means the card's difficulty estimate does not rise, its lapse or leech counters do not advance, and its stability is not shortened. The learner pays by seeing the card again sooner. For most learners that is a cost; for someone protecting a leech flag or a long interval it can be a *good* trade. It also moves the attempt to a later, possibly better-rested moment, which can change the eventual outcome honestly — a schedule effect rather than a rating manipulation, but still an effect. "No immediate rating advantage" overstates the case.

**2.2 The only zero-leverage design is the one nobody wants.** Counting all time and never invalidating has no alternative path to choose, so there is nothing to game. The review reaches this policy only through its punitive variant, rejects that variant for being unfair, and never notices that the unfairness is exactly what makes it ungameable. A two-minute call becoming Hard is not a memory judgement — but nothing is left to exploit, which is why the option has to be stated and rejected on its merits rather than left out.

It has a second cost that matters for the research programme, and this is the strongest argument for why it should be on the table and then rejected rather than omitted. Counting long interruptions puts the inflated time into the very distribution the calibration is meant to describe. The Hard median would partly absorb post-interruption time, which is not retrieval time at all, and the fitted ladder would then quietly encode interruptions as difficulty. So the zero-leverage option is also the zero-data option: it protects the rating by contaminating the measurement. That is the honest reason to reject it, and it belongs in the document.

**2.3 If the manual rating is written to the scheduler "just as Classic-mode ratings are", the row is not an interruption policy at all.** It is "fall back to Classic for this card". That is a simpler and more truthful description, and it makes the exploit obviously identical to one the product already accepts in Classic mode — with one difference worth naming: in Classic the learner chose to be self-assessed for the whole session, whereas here they can enter that mode per card, by waiting. It is a mode change disguised as an interruption.

---

## 3. Recommendation: Option A, measured, with a pre-committed trigger

The first pass recommended manual grading *because* it was neutral. That reason is withdrawn. The recommendation survives for a different one: manual grading is the least-bad price, **and it is the only option whose misuse is observable**.

The stated policy should be, in this order:

1. Interrupted reviews take a manual grade, with a one-line explanation that time cannot decide the grade.
2. Those rows are flagged, excluded from every threshold fit, and included in a secondary analysis that quantifies how much they would move the boundaries.
3. The invalid rate is instrumented from the first release, together with the fallback grade distribution and the amount of time already on the clock when the interruption began.
4. An escalation rule is written down **before** the data arrives: if the invalid rate exceeds a pre-set bound, or if invalid attempts cluster at the point where the ladder would turn Hard, the allowance is tightened or the policy switches to deferral.

Point 4 is the part the review is right to demand and the first pass omitted. Without it, "accept manual grading" is an assumption; with it, it is a policy with a tripwire.

One instrument deserves emphasis because it is the only direct evidence available. The learner's true intended grade is unobservable, so no measurement can prove gaming. But the app does know **how much time was already on the clock when the attempt was interrupted**. If invalid attempts cluster just as the clock passes the Easy boundary or approaches the Hard boundary, that is leverage evidence; if they cluster in the first few seconds, that is what genuine interruptions look like. The review's caution is right that clustering is a warning signal rather than proof, and it should be reported as such — but reporting it at all requires the field, and the field is cheap.

---

## 4. Measurement: agreed, with two corrections and one addition

The review's four quantities are the right set: elapsed seconds including away time, cumulative away seconds, a validity judgement, and a timing-rule version. Also agreed: the tolerance is cumulative rather than per interruption, it stays independent of card length and of the Easy/Hard budgets, and invalid rows are excluded from the fit rather than deleted.

**Correction 1 — monotonic time is already how the app measures.** Both the card clock and the sitting clock run on the device's monotonic elapsed-time source, not on wall-clock timestamps. So this part of the review is confirmation rather than a change, and the document should say "already satisfied" so that nobody budgets effort for it. What remains is the process-death case, and the existing behaviour is already the conservative one: if the app is killed, the attempt is lost rather than reconstructed from timestamps. That is the correct fallback and it should be stated as intentional.

**Correction 2 — "away" cannot be defined by lifecycle pause.** The app currently treats a lifecycle pause as backgrounding, and that event also fires for transient system dialogs, permission prompts, split-screen changes and configuration changes. A pause-based definition would count a two-second dialog as an interruption. Away should be derived from window focus plus visibility, with a short debounce; screen-off and device lock should count as away; in-app navigation that still shows the card should not.

**Addition — record the rating source and an invalidation reason.** Human-selected, automatic, or human-edited, plus a short reason code. The review's §6.2 asks, correctly, that a manual grade produced *because* timing was invalid not be treated as equivalent to an ordinary Classic label. Recording the source is what makes that filter enforceable rather than aspirational; without it the calibration cohort cannot be separated after the fact.

---

## 5. Three small things that decide whether the fitted ladder means anything

**Rounding runs in the wrong direction.** The reading allowance is rounded up to a whole second and dwell is truncated down, so the residual is biased low by up to roughly two seconds. Against an eight-second Easy budget that is a quarter of the budget. Either both quantities use one convention, or the arithmetic is carried in tenths and rounded only for display. This is not a detail; it is a systematic shift in the quantity being fitted.

**Clipping at zero throws away the fast tail.** If the residual is floored at zero before the medians are taken, every answer faster than the reading time collapses onto a single point, and the Easy median is pulled toward the boundary it is supposed to define. Keep the signed residual for the fit; clip only for display.

**The Again exclusion selects on the outcome.** Excluding Again rows is right, but the fitted medians then describe correct, timed, self-graded answers only. That should be stated, along with the number of rows it leaves, because a learner who fails often is fitted on a different subset than one who does not.

---

## 6. The sequencing problem the plan does not see: n = 1

The review's six-step sequence ends with "collect a sufficient post-change Classic-mode calibration sample". For this product that sample may never exist. Simplified is the default mode; Classic labels accrue only when a learner deliberately switches. The realistic population is one learner plus test fixtures. As written, the sequence can stall indefinitely, waiting for data that the product is not designed to produce.

The sequence should be split by dependency rather than by convenience:

- **Measurement and pause removal ship on their own merits.** They are strictly better than the current design and require no calibration to justify themselves. Gating them on a future sample is the wrong dependency.
- **Calibration ships behind a setting, with the current constants as the fallback**, and activates only once the sample exists. "Never activates" is an acceptable steady state. "Blocks the measurement fix" is not.
- **The evaluation is scoped honestly as a single-learner replay** on a consented backup export, not as a population study. The review's statistics — weighted agreement, confidence intervals, fairness by pace — remain the right measures, but with a few dozen rows the intervals on two cut-points will be wide. Saying so is part of the plan, not a disclaimer.

One feasibility point in the review's favour, which should be written down because it removes a suspected blocker: the backup format already carries each review's elapsed seconds, correctness, guess flag, pre-review state and post-review stability, difficulty and interval, with the questions alongside. A user-initiated backup is therefore already a sufficient dataset for the offline replay. No new schema is needed for the analysis. The only thing that cannot be reconstructed from old exports is away time and validity — which is exactly the column this change adds, and exactly why it should ship first.

---

## 7. The coloured clock: a product decision, not a research finding

The review's reasoning is internally correct: a live band turns a measurement into a target, and learners will rush, wait or change strategy to obtain a colour. That is a real risk and it should be recorded as one.

But the recommendation "keep the live colour off by default" is not a research conclusion. The colour was an explicit product requirement; it is built, contrast-checked across every theme, and awaiting merge. Research can say "this is an intervention with a predictable behavioural effect and it should be evaluated"; it has no standing to veto it. Saying otherwise in a document that is otherwise careful is what will get the rest of it discounted.

The document should instead recommend:

- **Record the risk as known and accepted**, with its mitigation stated plainly: the band is display-only, the grade is still computed from time, the learner can change the grade, and the band never enters a calibration label or a fit.
- **Fix the one inconsistency the band introduces.** At zero seconds the clock paints the fastest band, while the grader treats zero as untimed and returns Good. Two different statements about the same instant, and the learner will believe the clock. Either the clock withholds a tint until the first second has elapsed, or zero is treated as Easy in the display and the grader's untimed path is given its own colour. The first is cheaper and does not change grading.
- **Treat the sequence as "colour ships as an owner-decided layer, evaluated separately"**, not as "colour is withheld until the measurement is settled". The review's step 2 currently says the opposite.
- **If evidence is wanted**, the only feasible test here is a personal comparison across sessions with the colour on and off, reported as a single-learner observation. That is worth doing; it is not worth generalising from, and the document should say so.

---

## 8. The session clock: one decision, and a cheap resolution

If dwell becomes wall time, a card's question time can exceed the sitting total, because the sitting clock excludes background. The summary currently derives explanation time as the sitting total minus question time, clamped at zero, so it cannot go negative — it will silently under-report, possibly to zero.

There is a cheap resolution that avoids new arithmetic: keep two quantities and stop conflating them. **Dwell**, measured as wall time, decides the grade. **Question time**, measured as active time, feeds the sitting summary. The summary answers "how was this sitting spent", not "how long was each attempt"; a call taken mid-session genuinely is not study time, even though it is part of that attempt's dwell. With that split, no clamping is needed and nothing else changes.

This should be written as an explicit choice, because the two readings are both defensible and the difference will otherwise be discovered during implementation.

---

## 9. Smaller corrections to fold in

These come from the earlier round and are still open. They are listed here so the revised brief can close them in one pass.

- **The boundary rule is a decision rule, not a reconstruction of the learner's scale.** Medians plus midpoints is a nearest-median classifier with equal priors and equal costs, and it will produce fewer Easy and fewer Hard grades than the human did. Say that, and compare it against a boundary chosen to maximise weighted agreement, which will separate the cut-points when the label distribution is skewed.
- **The baseline is not fair.** The calibrated rule is fitted to the learner's labels; the current constants never were. Report against a learner-specific constant and against the current constants with one global scale factor refit on the same data, or the improvement is arithmetic rather than informative.
- **Raw-band correctness separation is a precondition, not a check.** If the raw bands show no reproducible accuracy difference, the ladder is cosmetic and should be demoted regardless of how well it agrees with self-report.
- **Classic labels may be time-anchored.** The clock is visible in Classic mode too, so a self-grade can be partly a reaction to the number just watched. A calibration session with the clock hidden gives cleaner labels.
- **New cards should be graded Again or Good only.** Forcing every first exposure to Good is not neutral: the first rating sets initial stability and difficulty, so "wait and see" is really "assume average". If the full range is kept instead, the simulation must pre-register the expected compression of initial intervals and say what happens if it is large.
- **Calibration activation is a product-flow problem, not only a shrinkage problem.** Small samples are handled by shrinking to the prior; no samples at all are handled by a deliberate path — a short guided calibration, or a periodic prompt — or the feature never runs.

---

## 10. What I accept, what I amend, what I reject

**Accept:** the correction to "incentive-proof", with the price stated; the precise measurement quantities; cumulative rather than per-interruption tolerance; the monotonic-clock requirement as confirmation of existing behaviour; a separate rating-source field; excluding invalid rows from the fit; the evaluation measures for the interruption policy itself; and the rule against changing measurement, policy, thresholds and display in one release.

**Amend:** the decision table keeps four outcomes instead of five, because "automatic Hard" and "resume and use elapsed time" both collapse into count-everything; deferral is described as low-leverage rather than neutral; manual grading is recommended with a pre-committed trigger rather than as a neutral choice; "sufficient sample" stops being a gate on shipping the measurement; and "away" is defined by focus and visibility rather than by lifecycle pause.

**Reject:** "keep the coloured clock off by default" as a research conclusion, and the sequencing that treats it as a precondition. Keep the risk, keep the mitigation, keep the zero-second inconsistency as a defect to fix, drop the veto.

---

## 11. Where this leaves the design

The principle the review proposes is one I would keep almost verbatim:

> Use elapsed time only when the attempt was continuously interpretable; count short absences, invalidate long interruptions, and never convert invalid timing automatically into a favourable scheduler grade.

The addition this pass makes is that the last clause is necessary but not sufficient. Manual grading does not convert invalid timing into a favourable grade *automatically* — but it does hand the learner the choice, and a learner who expects Hard will sometimes take it. So the design is not "a fallback that cannot be gamed". It is "a fallback whose gaming is bounded, priced, observed, and abandoned if the observation turns bad".

Stated that way, the recommendation is defensible to a careful reader, and it matches what the product can actually do.

---

## 12. Questions back

1. Is a pre-committed invalid-rate bound plus a boundary-clustering test the right tripwire, or is there a cheaper observable that detects leverage earlier — for example, comparing the fallback grade distribution against the ambient Classic distribution?
2. Should the escalation path be fixed in advance (invalid rate above the bound switches to deferral permanently) or reviewed per release? The first is more credible; the second is more forgiving of a badly chosen allowance.
3. Given that the zero-leverage option contaminates the calibration distribution, is it fair to say the choice is between a rating risk and a measurement risk? If so, that framing belongs in the brief rather than being left implicit.
4. For the calibration session, is a clock-hidden Classic sitting acceptable as a deliberate product flow, or does hiding the clock change the very self-grades we are trying to learn from?
5. If the sample never materialises because Simplified is the default, is "calibration available but dormant" an acceptable end state for the research programme, or should the ladder be reconsidered on analytic grounds alone?
