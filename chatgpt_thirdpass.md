# Third pass: the rule is settled, and it makes the design smaller

**Responding to:** the third review, and to the owner's decision on what the feature is for.

**The decision.** The objective is automatic grading of Again / Easy / Good / Hard without user intervention, with manual grading available to the learner. The learner is assumed honest and is not assumed to be trying to game the algorithm — including through controls that exist, such as the pause button. The dwell rule is now fixed: **count time only while the question is on the screen; when it is not on screen, do not count it.** A pause is the learner's own statement that they are not working on the card right now, and it is treated exactly like the card leaving the screen.

Under those statements, most of what the previous two passes argued about is no longer a design question. That is not a criticism of the reviews — the analyses were correct under the assumption they were given, which was that a learner might exploit the measurement. The owner has replaced that assumption with a different one, and this document sets out what the design collapses to and what is genuinely left to change.

The short version: the dwell rule you have chosen is **already how the app measures**, and the pause button is consistent with it rather than an exception to it. So nothing in the clock, the timer or the grading path has to change. What remains is provenance for the adaptive loop — a source field, a version field and a small versions table — plus two small corrections. None of it changes a single grade today.

---

## 1. What the honesty assumption retires

Assuming the learner is not trying to game the app does not merely soften the anti-gaming machinery; it removes the reason for it. The list of things that were being designed around, and what happens to each:

| Was being designed around | Status now |
|---|---|
| An invalid-timing bucket with its own grading policy | Not needed. Interrupted time simply is not counted. |
| An absence allowance, and testing 10 / 20 / 40 seconds | Not needed. There is nothing to tune, because nothing is being tolerated. |
| Manual fallback as an interruption policy | Not needed. The learner can grade manually whenever they like, for any reason. |
| Deferral versus manual grading as an integrity choice | Not needed. There is no integrity choice left to make. |
| A tripwire on the invalid rate, and an escalation path | Not needed. It was a monitor for an assumption we no longer hold. |
| A counterfactual automatic grade, kept for analysis only | Not needed. It existed to measure leverage. |
| Counting all time as the "zero-leverage" option | Rejected for a simpler reason: it would charge the learner for a phone call. |

Two of the third review's five refinements therefore become moot: the request not to describe the tripwire as detecting gaming, and the request not to invent a percentage for it. Both were right, and both are now unnecessary, because the tripwire is gone rather than reworded.

This is worth saying plainly in the document that goes to the team: **the interruption policy is not being simplified, it is being deleted.** A reader who sees the old material alongside the new will otherwise assume the allowance still exists somewhere.

---

## 2. Why the honesty assumption is reasonable here

It is defensible for reasons specific to this product, and those reasons should be written down so the assumption is a decision rather than a convenience.

- **The data is the learner's own.** There is no shared ranking, no certificate, no score anyone else sees. A self-serving grade produces a worse schedule for the person who chose it. Gaming is self-harming, which is the strongest available argument for not policing it.
- **The learner already has an unrestricted manual path.** Classic mode exists, and Simplified mode already offers a "change grade" control after the answer is checked. Anti-gaming rules in Simplified mode would be protecting a door that is already open by design.
- **The alternative has a real cost.** Every anti-gaming rule charges the honest learner for the dishonest one: an interruption becomes a policy event, a phone call becomes a decision about grading, and the session gains a new failure path.

**What would invalidate the assumption**, and should be recorded as a revisit trigger: any feature that makes the rating externally visible or externally rewarded — leaderboards, shared progress, certificates, streaks that other people can see, or a competitive mode. If one of those ships, the leverage question returns, and the analysis in the second pass becomes relevant again.

There is one honest cost to state as well. If a learner does deliberately game the measurement, the design will not notice and will not correct it. The consequence is confined to that learner's own schedule. That is the trade being accepted, and it should be written down as accepted rather than left implicit.

---

## 3. The dwell rule, and how the app already implements it

The rule is: count monotonic time while the question is on screen, and only then.

The app already does this:

- The card clock runs on the device's monotonic time source, so clock changes and time-zone changes cannot distort it.
- The clock stops when the card leaves the viewport, when the app leaves the foreground, and when the answer is checked. The sitting clock is separate and follows the same idea.
- The measured dwell — the on-screen time — is the quantity the ladder reads. It is not wall-clock time since the card appeared.

So there is no clock redesign and no timer redesign, and the **manual pause does not need to be removed**. Under the honesty premise, a pause is the learner telling the app "I am not working on this card right now". That is the same statement the app already infers when the card scrolls out of view or the app is backgrounded, and it should be treated the same way: the time is not counted. There is no integrity question to answer and nothing to remove.

The practical effect is that a learner who steps away for two minutes and pauses — or does not pause, and simply leaves the app — gets the same dwell: the time they actually spent with the card. That is the desired behaviour, and it holds whether the learner uses the button, scrolls away, or switches apps.

The one thing to keep is the *reported* quantity, not the control: the clock shows the dwell that will be graded, and it freezes when it is paused, so what the learner sees and what the ladder reads stay the same number. That is already the behaviour, and it is the only requirement the pause places on the design.

**One definition worth pinning down.** "On screen" should mean: the app's window is showing, the screen is on, and the card overlaps the viewport. That adds one thing to the current geometric check — window focus — because pulling down the notification shade or opening a system dialog covers the app without moving the card, and a covered card is not on screen even though its coordinates are unchanged. Ordinary in-app navigation that leaves the card visible should not stop the clock.

A deliberate looseness is worth noting so nobody "fixes" it later: the current check counts the card as on screen if any part of it is in the viewport. That is intentional. A learner reading a long passage above the question is doing the work the question requires, and the reading allowance already pays for passage length. Requiring full visibility would under-count exactly the long questions the allowance was built for.

---

## 4. What interruptions now mean: a data note, not a policy

With off-screen time excluded, a genuine interruption simply does not enter the measurement. There is no invalid bucket, no fallback, and no manual gate. The same is true of a pause: it is another way of saying the same thing, so it needs no separate treatment and no extra column.

The residue is a small measurement effect rather than a governance problem: a break can make recall easier or harder on returning, so an interrupted attempt can land slightly faster or slower than an uninterrupted one. That is noise, not bias worth designing around, and it should be recorded as a limitation of the calibration sample rather than solved with a policy.

If the team wants to be able to inspect it later, one optional field is enough: cumulative time not counted, whether paused or off screen. It is not used for grading and not used for any decision. It exists so that a future analysis can, if it wants, set those rows aside. If schema churn is unwelcome, this field can be dropped entirely without weakening anything in this design.

---

## 5. Calibration is in scope, and it is aimed at outcomes

The objective is not only to grade automatically but to improve the learner's exam performance, so the app should adapt the ladder to the person using it. That raises one question that decides the whole design: **calibrated against what?**

Not against self-grades. A self-grade records felt difficulty, which is a weak proxy for delayed recall, and the earlier passes already noted that it is anchored by the visible clock. Matching it can encode the learner's overconfidence. The target that matters is the outcome: for each grade, whether the material was actually recalled when the scheduler brought it back, compared with what the scheduler predicted for that card. That comparison is available from data the app already writes — each review carries the grade and the interval, and the next review of the same card carries whether it was correct.

That has three consequences worth stating plainly.

- **The activation problem disappears.** Earlier passes worried that calibration might never run, because it depended on Classic-mode self-grades that most learners never produce. Outcomes accrue in the default study mode, for every review, without asking the learner to do anything. The loop runs on the same data that already schedules the cards.
- **The scheduler is already adapting.** The app fits its own scheduling weights to the review log. The ladder is a second, smaller knob, and the two must not be turned at the same time or neither result can be interpreted.
- **Self-grades and overrides become a check, not the target.** Overrides are useful evidence and useful for the learner, but they are selected on disagreement: they consist entirely of cards where the ladder was wrong, from both directions, so fitting to them narrows the middle band even when the true bands are wider. They should be reported, not fitted.

What the data has to gain is **provenance**: which grades were automatic, which were chosen or edited by the learner, and which ladder version was in force. Without those, an outcome cannot be attributed to the rule that produced it, an adjustment cannot be reversed, and an improvement cannot be told from a change in the cards. One source field per review, one version field per review, and one small table of ladder versions — that is the entire database change, with no history rewrite.

The full plan, including the damping rules, the acceptance criteria and the single-learner caveats, is in the companion note on learner-relative calibration.

---

## 6. What still genuinely needs fixing

Small, and all of it independent of the interruption question.

- **Rounding runs the wrong way.** The reading allowance is rounded up and dwell is truncated down, so the measured thinking time is biased low by up to about two seconds. Against an eight-second Easy budget that is material. Use one convention, or keep finer precision internally and round only for display.
- **Do not clip the residual at zero when fitting.** For analysis, keep the signed value of dwell minus reading time. Flooring it collapses every fast answer onto one point and pulls the Easy boundary toward the data it is supposed to define. Clipping is a display and operational concern, not an estimation one.
- **Do not calibrate on first exposures.** A new card is encoding, not retrieval. Its dwell can be recorded, but it should not be used to set retrieval thresholds. Whether a new card may receive Easy or Hard is a separate scheduling question and should be decided by replay, as the third review says.
- **Zero seconds is two different claims.** The grader treats zero as untimed and returns Good; the coloured clock paints the fastest band at zero. The cheapest fix is to withhold the tint until the first second has elapsed, which changes neither the untimed rule nor the schedule.
- **The session summary is already safe.** Because dwell is on-screen time, it cannot exceed the sitting's active total, so the earlier concern about explanation time going negative or being clamped no longer arises. That class of complexity disappears with the rule.

---

## 7. The coloured clock

The band can ship. It is an owner decision, and the third review's acceptance of that is the right framing.

One argument survives the honesty assumption and one does not. The **incentive** argument does not: an honest learner is not rushing to beat the grader. The **reactivity** argument does: an honest learner can still behave differently when a colour is watching — speeding up when green, hesitating when amber — and that changes the very thing being measured. Reactivity is not dishonesty, so this is a measurement caveat, not a governance objection. It belongs in the notes, not in a veto.

The conditions worth keeping are modest: the band is display-only, it never changes the grade, it never enters a calibration label, and it is not described as a memory-strength indicator. Fix the zero-second mismatch before release. A personal on/off comparison across sessions is optional and can be reported as a single-learner observation.

---

## 8. Sequencing, which is now three short steps

**Step 1 — record provenance.** Nothing in the clock, the timer or the grading path changes: on-screen time is already what is counted, and a pause is already treated as "not working on this card". Add the grade-source field, the ladder-version field and the small versions table. Fix the rounding convention and the zero-second tint. No change to the ladder, no change to the scheduler, nothing to replay.

**Step 2 — report, do not adjust.** Compute, from the review log, whether each grade's outcomes match the scheduler's prediction, and show it with its sample size. This is also how the first real evidence arrives, and it changes no grade.

**Step 3 — learner-relative ladder.** First as a suggestion the learner can apply, then with damped automatic adjustment once the loop has proved stable on real data. The current constants stay as the prior and as the fallback, and the whole loop pauses while the scheduler's own weights are being refit. The scope statement from the second pass still applies: a single learner's history supports within-learner comparisons and wide intervals, not population claims.

**Step 4 — verify against performance.** Keep an adjusted ladder only if lapses per review at a fixed retention target improve by more than the pre-agreed margin. An adjustment that holds retention but doubles review time is not an improvement.

Explicit non-goals, to stop the earlier material being reintroduced by accident: no absence allowance, no invalid-timing bucket, no deferral, no gaming tripwire, no wall-time dwell, no history rewrite, no fitting to automatic proposals, and no moving the ladder and the scheduler weights in the same window.

---

## 9. What survives from the three reviews

The third review's five refinements, in order:

1. **Do not call the tripwire a gaming detector.** Moot — the tripwire is removed. The underlying point stands and should be kept as a principle: a clustering pattern is evidence of a possible effect, never proof of intent.
2. **Do not invent an invalid-rate percentage.** Moot for the same reason, and the principle is still good: measure before setting a number.
3. **Keep grading dwell separate from session accounting.** Already satisfied, and more simply than by convention: both are on-screen time, so they cannot diverge. Keep the labels distinct anyway, because they answer different questions.
4. **Calibration should be optional and dormant-capable.** Accepted in spirit, and changed in one respect: the app should adapt the ladder to the learner, because the objective is exam performance rather than faithful grading. What changes is the target. Fitting to self-grades was the earlier plan and would not have activated for most learners; fitting to outcomes needs no mode change and runs on the default study data. Calibration remains dormant whenever the evidence is thin.
5. **Median midpoints are a baseline, not the answer.** Accepted. Compare the fixed constants, the constants with one global scale factor, the shrunken median-midpoint fit, and a weighted-agreement boundary; report uncertainty; prefer the simpler model when the data cannot separate them.

From the earlier passes, the durable pieces are: the monotonic clock, the on-screen definition of dwell, the pause as another way of declaring "not now", the signed residual, one rounding convention, the Again selection caveat, the uncalibrated-baseline comparison, the raw-band correctness check as a precondition rather than an afterthought, and the warning that the live band is an intervention.

Retired from the earlier passes: the wall-time proposal, the absence allowance, the invalid bucket, the manual fallback as an interruption policy, the deferral comparison as a live decision, and the escalation trigger.

---

## 10. One question, and it is about product rather than measurement

Should the first adaptation be applied silently, or proposed to the learner for confirmation? Silently is simpler and matches "the app should calibrate itself"; proposing is safer for the first version, because it makes a wrong adjustment visible and reversible by the person it affects. The recommendation in the calibration note is to propose first and automate once the loop has proved stable, but this is a product call rather than a measurement one.

Everything else in this document is a consequence of the rule, not a decision left open.
