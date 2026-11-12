# First pass: does removing the manual pause fix the timing problem?

**Responding to:** the review's §2.1 (rule 3), §5 (recommendations 2 and 3), and the pause discussion throughout.
**Short answer:** removing the manual pause helps, but only if the outcome for invalid timing is chosen carefully. The obvious choice — "no pause, so grade whatever time we have" — re-opens the same incentive through the background and scroll-away doors. The obvious fallback — "invalid timing gets a neutral Good" — is *worse* than the pause it replaces, because it turns a would-be Hard into a Good. The rule that works is: **no manual pause, one dwell quantity (wall time since the card appeared), a small absence allowance, and an invalid-timing fallback that cannot be better than an honest grade.**

---

## 1. What removing the manual pause actually fixes

The pause button matters in exactly one place: **Simplified mode**, where the automatic grade uses dwell. In Classic mode the learner grades themselves, so stopping the clock changes nothing.

In Simplified mode, the pause is currently the only zero-cost stop. Revealing early is not a free stop — an empty selection grades Again — so the pause is uniquely attractive: it converts a possible Hard into whatever the banked time happens to be. That is a real, cheap, learnable exploit, and it is the one the review is right to close.

Removing it gives three concrete benefits:

1. **The exploit's cheapest form disappears.** No single tap converts effort into a better grade.
2. **The calibration data becomes usable sooner.** The review's biggest data caveat (§4, step 1) is that historical pause segments were never recorded, so past rows cannot be repaired. With the button gone, the largest unobservable contaminant never enters the dataset in the first place.
3. **The rule gets simpler to explain.** "The clock runs from the moment the card appears until you answer" is a sentence a learner can hold in their head. "It runs unless you tap it, and then it doesn't count" is not.

## 2. What it does not fix

Two doors remain, and both are currently open in the product:

- **Backgrounding the app.** The clock pauses when the app leaves the foreground. A learner can put the phone down, think for two minutes, come back, and the thinking is invisible to the grader.
- **Scrolling the card out of view.** Visibility pauses the clock. The same think-away move works without leaving the app.

Neither is as convenient as a button, but both are available and both are exactly the behaviour this change is meant to stop. So "remove the pause" is not itself a complete rule; it is a precondition for one.

There is a second thing it does not fix: **the coloured clock is still a live target.** Removing the pause reduces gaming by deliberate stopping; it does nothing about rushing to stay green. These are two different interventions and should not be presented as one.

## 3. The outcome question, and why "neutral" is the wrong answer

Suppose the pause is gone and a card is interrupted for three minutes. The review's current proposal is a neutral Good. That is defensible as an *honesty* position — we do not want to call a card Hard because a phone rang — but it fails as an *incentive* design, because of how it compares to the alternatives:

| Learner's situation | Honest grade | "Invalid → Good" |
|---|---|---|
| Knew it in 4 s | Easy | Good (**worse**) |
| Normal retrieval in 20 s | Good | Good (same) |
| Struggled for 50 s | Hard | Good (**better**) |

A fallback that is worse than Easy and better than Hard is a **reward for stalling**, aimed precisely at the learner who is struggling. It replaces a cheap tap with a slightly less cheap backgrounding, and it pays better than the tap did, because the tap at least required them to sit there.

So the invalid-timing outcome cannot be a fixed grade at all. It has to be one of:

- **Manual grading for that card** (the four buttons appear, exactly as in Classic mode). No automatic credit, no automatic penalty, and the human decides. This is the review's own "ask for a manual grade" fallback and it is the right one.
- **Defer the card** (no review written, card returns next session). Also incentive-free, but it costs the learner the review they just did, which feels punitive for a genuine interruption.
- **Grade Again** — incentive-free but unfair; a phone call is not a memory failure.

Recommendation: **manual grading**, with a one-line explanation ("This card was interrupted, so time cannot decide the grade"). It is the only option that is both fair to interruptions and unattractive to stalling.

## 4. The model I would propose

One quantity, wall time, with a bounded interruption rule:

- **dwell** = seconds from the card becoming visible and answerable to the answer being committed, **including** time spent backgrounded or scrolled away.
- **absence allowance** = a small fixed number of seconds of interruption above which the review is not time-graded. Starting value: 20 seconds; test 10/20/40 in the sensitivity analysis. It is an interruption tolerance, not a pace parameter, so it does not scale with card length or learner.
- **invalid** = total away time exceeds the allowance. The review is still recorded, flagged as not time-graded, and takes the manual-grade path.
- **committed pause** remains: the clock stops at answer commit, because reading the explanation is not part of the attempt.

Two alternatives are worth stating explicitly, because the difference matters:

| Definition | Free thinking | Interruptions | Side effects |
|---|---|---|---|
| Active time only (status quo minus the button) | unbounded — background and scroll away are invisible | never punish | the exploit simply moves from the button to the home screen |
| Active time plus a small uncounted allowance | up to the allowance, every card | tolerated | a hidden budget of thinking time; the cap must be very small to be safe |
| **Wall time with an allowance (proposed)** | none | bounded: short ones are counted, long ones invalidate | a short glance is charged; the summary arithmetic needs one adjustment (below) |

The middle option looks attractive but is the weakest of the three: any allowance that is not counted is a per-card budget of invisible thinking, and at a generous value (20 s) it is larger than the default Easy budget, so it can flip a genuine Hard into an Easy. Counting the time and using the allowance only to decide when to stop guessing removes that.

Why wall time rather than active time: the app cannot distinguish "call from my mother" from "went to check the textbook", and it should not try — that is a cheating question, not a timing one. Counting the time and bounding the tolerance behaves the same way for an honest interruption and a dishonest one, and it closes the pause, the background and the scroll-away doors with one rule.

Why an allowance at all: without it, a two-minute call becomes Hard, which is a false memory signal and would poison both the schedule and the calibration labels. The allowance converts "long interruption" into "no automatic judgement" rather than into a grade.

Decision table for the resulting product:

| Situation | Dwell | Grade |
|---|---|---|
| Answered with no absence | wall time | ladder |
| Glanced away briefly (within the allowance) | wall time, counted | ladder |
| Backgrounded or scrolled away beyond the allowance | recorded, flagged invalid | manual grade for that card |
| Untimed review (legacy, imported, clock disabled) | none | Good (as now) |
| Wrong, empty or declared guess | irrelevant | Again |
| Reveal with no answer selected | — | Again (an empty selection is a skip) |
| Classic mode | irrelevant | learner's own grade |

The critical rows are the third and the fourth. The third must never default to Good. The fourth is fine because no choice was involved — there is no behaviour to reward.

## 5. Consequences for the review's rules

If this is adopted, the review's §2.1 changes as follows:

1. Rule 3 ("deliberately paused → neutral fallback") is replaced by "timing invalid → manual grade, excluded from calibration". The manual pause no longer exists, so the rule is about interruption, not intent.
2. The schema requirement changes. There is no pause flag to add. One column is needed, and it should be **away seconds, not a boolean**, so the allowance can be re-tuned offline later without re-collecting data. This must ship **before** any calibration labels are collected, because it changes the dwell distribution.
3. Rule 2 ("untimed → Good") is unchanged, but its justification should be explicit: it is a neutral fallback for *unknown* timing, not a grade for a choice.
4. §5 recommendation 2 should be reworded: pause is removed as a feature, not punished as a loophole. The lifecycle pauses that remain are automatic and bounded, not discretionary.
5. §4, step 1 gains a version gate: fit calibration labels only on reviews recorded under the new dwell definition. Older rows can still be used for the correctness-versus-dwell sensitivity analysis, but not for fitting boundaries.

## 6. Calibration implications

This is the part that makes the change worth doing *now* rather than later.

- **Start the clock definition before collecting labels.** The review's plan needs 8+ Easy, 8+ Good and 8+ Hard Classic labels per learner. If the dwell definition changes after those labels are collected, the labels and the times no longer share a measurement model, and the first cohort is wasted. Ship the validity rule first, then invite calibration.
- **Flag invalid rows, do not delete them.** "Interrupted" is itself information about the learner's environment; deleting it would hide that some learners are interrupted often, which matters for fairness.
- **Keep the allowance out of the fit.** It is a data-quality threshold, not a pacing parameter; tuning it inside the boundary fit would let it absorb variance that belongs to the learner.
- **One thing to watch:** with the pause gone, the reviews that remain include short glances, which lengthens the upper tail of the residual distribution without moving the medians much. That is fine for a median-midpoint rule, but it will make the review's proposed 10-second minimum Good band bite more often. Report the share of reviews that fall back to manual grading; if it is large, the allowance is too tight for that population.

## 7. Accessibility

Removing the pause is acceptable **only** alongside the review's recommendation 3: an obvious untimed or manual path, and the option to disable time-based grading.

The reasoning: the pause is not an accessibility accommodation for retrieval. Extra time helps a learner read, locate and physically respond; the correct accommodation for that is to stop grading on speed, not to let them stop the clock while still being graded on the reduced time. Keeping a discretionary pause as an accommodation is also unfair in the other direction — it gives the learner who knows about it a grade advantage over the one who does not.

The real accessibility lever is the setting, plus a neutral clock display. Keep the ability to disable speed grading as a first-class option rather than a hidden one; make the coloured band the optional extra, not the accommodation.

## 8. Product consequences, briefly

- The clock toggle, its paused label, its accessibility state and its tests all go.
- The clock becomes a passive readout that starts with the card and freezes at the answer.
- Background and scroll-away no longer pause the dwell; they only decide whether the review stays valid.
- The session summary needs one decision. Its "explanation time" is derived as session total minus question time, and the session total excludes background. Wall-time dwell can therefore make question time briefly exceed the session total when a card included a short background stay. Either let the session clock run while a card is open, or clamp the reported explanation time. It is rare, but it must be chosen rather than discovered.
- An "interrupted" path appears in Simplified mode: manual grade, with a short explanation. This is new UI, and it is the price of not rewarding stalling.

## 9. Questions back to the review

1. Is wall time with a fixed absence allowance preferable to active time with a cap, given that the app cannot distinguish an interruption from a deliberate think-away — and is the summary-arithmetic adjustment an acceptable price?
2. Is 20 seconds the right order of magnitude for the allowance, or should it be tied to the hard threshold for long cards?
3. Should interrupted cards be flagged and excluded from calibration but still written to the schedule with the manual grade, or deferred entirely? The first keeps the session flow; the second avoids a low-confidence grade entering FSRS.
4. If the manual-grade fallback is used often, does that mean the allowance is wrong, or that the learner needs the untimed path switched on?
5. Does removing the pause change the review's recommendation on the coloured clock? With no pause, the only remaining behavioural lever is the visible band, so the case for a neutral display is stronger, not weaker.

## 10. Summary

Removing the manual pause is a genuine improvement and should be done. It eliminates the cheapest exploit, simplifies the rule to one sentence, and lets calibration data be collected cleanly from the start — but only if the loss of the pause is paired with a rule for interruptions. That rule should be: count wall time, tolerate a short absence, and when the absence is too long give the card to the learner to grade rather than to the algorithm. Any fallback that resolves to Good will simply move the gaming from a button to a backgrounded app, and will reward it more.
