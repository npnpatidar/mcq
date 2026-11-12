# Review of learner-relative dwell calibration

## Overall assessment

This is a substantial improvement in the framing of the problem. The note correctly treats a grade as an instruction to FSRS about the next interval, not as a label that should imitate a learner's feeling. It also makes the single-learner scope explicit, separates the ladder from FSRS's own weight fitting, preserves history, and proposes a staged path from measurement to reporting to supervised adjustment.

I agree with the central direction: **do not fit the ladder to self-grades or to the subset of manual overrides; evaluate whether the grades are associated with the retention outcomes the scheduler predicted.** That is a better-aligned target for the stated product purpose.

I would not, however, ship the automatic adaptation loop exactly as written. There are several important specification gaps. The most consequential is the definition and timing of the predicted-recall value paired with the next review's outcome. The statistical safeguards also need to account for repeated reviews of the same cards, topic clustering, repeated looks at rolling windows, and the fact that the grade itself changes the interval and therefore the opportunity to observe a lapse.

My recommendation is to approve the **measurement and reporting stages**, run a pre-registered single-learner comparison before automatic movement, and defer automatic adjustment until the prediction timestamp, evidence unit, and adoption outcome are made precise.

---

## 1. The estimand is directionally right, but the prediction timestamp must be specified

The proposed quantity is observed success at the next review minus the scheduler's predicted recall. That is a sensible diagnostic, provided the prediction and outcome refer to the same point in time.

The note says to store “the predicted recall at review time” and then pair a grade on one review with correctness at the next review. This wording is ambiguous in a way that matters:

- The grade is assigned at review *t*.
- The outcome is correctness at the next review, *t+1*.
- The relevant prediction is the probability of recall immediately before the answer at *t+1*, not the predicted recall at the earlier review *t*.

A prediction made at *t* for the scheduled due date is not necessarily the prediction for the actual next review. The learner may review earlier or later than scheduled. If the outcome is paired with a due-date prediction while the learner actually answers days later, the calibration error partly measures scheduling adherence rather than the ladder.

I would store two distinct quantities, with unambiguous names:

1. **Scheduled-due forecast:** the predicted recall at the due time chosen after review *t*. Store it with the review that generated the schedule. This measures what the scheduler intended.
2. **Next-review forecast:** the predicted recall immediately before the answer at review *t+1*, using the state and scheduler parameters actually in force at that moment. Store it with the *t+1* review and link it to the preceding review. This is the value to pair with the observed correctness at *t+1*.

The second quantity is the cleaner calibration comparison for “what happened when the learner next saw the card”. Store the scheduler-weight version used to produce it. The first is useful for diagnosing schedule adherence and the scheduler's intended target, but should not be silently substituted for the second.

There is also a distinction between calibration and causal effect. The note is right to call observed-minus-predicted a descriptive diagnostic: the grade was assigned from dwell, and the grade itself influences the interval. This statistic does not tell us what would have happened if the same attempt had received another grade. It can identify a pattern worth investigating; it cannot, by itself, prove that moving a cut-point will improve retention.

**Recommendation:** retain the proposed estimand, but define the prediction timestamp and pairing key before implementing it. Make the scheduled-due and next-review forecasts separate fields if both are retained.

---

## 2. Observed-minus-predicted is a useful first diagnostic, not a complete calibration score

The difference in mean outcomes is interpretable:

- positive: observed recall exceeded predicted recall;
- negative: observed recall fell short of predicted recall.

That makes it a good dashboard statistic and a useful first-pass control signal. It is not the only possible measure, and the note is right to ask whether Brier score or log loss would be better.

I would not replace the mean difference immediately. Instead, report a small set of complementary diagnostics:

- **Mean calibration error:** mean of outcome minus predicted probability, overall and by grade. Easy to explain.
- **Brier score:** mean squared error of predicted probabilities against the binary outcome. This assesses probabilistic accuracy, not just average bias.
- **Calibration by prediction range:** useful for detecting a scheduler that is systematically overconfident at some predicted probabilities but not others.
- **Counts and uncertainty:** always show the number of distinct cards as well as the number of review pairs.

A mean error of zero can conceal serious miscalibration in opposite directions across prediction ranges. Conversely, a Brier score can worsen because predictions become less sharp even when the average bias improves. These are complementary views, not interchangeable adoption criteria.

The proposed rule that “all three grades deviate the same way” points to a scheduler problem is a useful heuristic, but not a proof. A common shift can also arise from a change in the learner's study conditions, the mix of cards, or the timing of reviews. The diagnostic should say “do not move the ladder on this evidence” rather than asserting that the scheduler is definitely at fault.

**Recommendation:** use mean calibration error for the first report, add Brier score as a secondary diagnostic, and keep the language descriptive.

---

## 3. Card clustering is necessary; topic clustering and small-cluster limits also matter

The note correctly rejects an ordinary binomial interval over all review pairs. Repeated outcomes from one card are correlated, so treating every review as independent would make the uncertainty look too small.

A card-cluster bootstrap is a sensible improvement, but two further conditions are needed:

1. **Report the number of distinct cards.** Thirty pairs from thirty cards is very different evidence from thirty pairs contributed by three cards. The minimum evidence rule should specify both pair count and distinct-card count.
2. **Consider topic clustering.** Cards in the same topic or passage may share vocabulary, difficulty, and study context. Resampling cards independently can still understate uncertainty if many cards come from a small number of related topics or passages.

A two-stage bootstrap (sample topics or passages, then cards within them) is conceptually appropriate if the app has reliable topic/passage groupings and enough groups. If there are too few groups, do not pretend the interval is reliable. Report the limitation and leave the cut-points unchanged.

The note's proposed “at least 30 paired outcomes for a grade” is not enough as written. It should say whether that means 30 distinct cards, 30 pairs, or both. The additional requirement of at least 10 in each of the two classes being compared also needs to be made explicit:

- for a c1 decision, which evidence is required from Easy and Good;
- for a c2 decision, which evidence is required from Good and Hard.

I would require minimum counts in both adjacent grades and minimum distinct-card counts in both, rather than allowing a large number of repeats from a handful of cards to unlock an adjustment.

**Recommendation:** keep card-clustered uncertainty as the minimum standard; add topic/passage clustering where the data support it; otherwise label the interval exploratory and do not adapt from it.

---

## 4. Rolling windows create a repeated-testing problem

The proposal uses the most recent 500 pairs, capped at 90 days, and checks whether a 90% interval excludes zero. If the app evaluates a new rolling window after every review, it repeatedly tests the same underlying data with heavily overlapping windows.

Even if each individual interval has a nominal 90% coverage, repeatedly looking for an interval that excludes zero increases the chance of eventually finding an apparently meaningful difference by chance. The five-percentage-point floor does not remove that problem.

The proposed “one cut at a time” and “wait a full window” rules help reduce instability, but “a full window” needs a precise operational definition. Does the next decision wait for 500 new pairs, or does it use a new rolling set of 500 that overlaps almost entirely with the last one? Those are very different procedures.

Possible approaches:

- **Non-overlapping decision windows:** make a decision only after a fresh evidence block has accumulated. This is simple and auditable, though slower.
- **Pre-registered sequential monitoring:** specify an alpha-spending or other sequential procedure before looking at results.
- **Proposal-only stage:** show a suggested change but do not apply it until a separate validation block supports it.

For a first version, I favour non-overlapping evidence windows and proposal-only changes. It is easier to explain and much harder to accidentally turn repeated peeking into a false discovery.

The 90% interval, five-point floor, 500-pair window, and 15% step should be described as **provisional design choices**, not as statistically validated conservative values. “Conservative” depends on the number of independent cards/topics, the number of repeated looks, and the cost of a wrong adjustment.

---

## 5. The next-review outcome is selected by the review process

There is a further issue with using the next review's correctness as the outcome. A review pair exists only when the card is seen again.

Some cards may not return during the observation window; some may be delayed; some may be suspended or removed; and the grade affects the interval at which the next review is expected. The observed pairs are therefore not necessarily a neutral sample of all attempts.

This does not make the proposed signal unusable. It means the report should disclose:

- how many reviews have a linked next review;
- how many are not yet followed up;
- how many were followed up outside the expected timing range;
- whether missing follow-ups differ by grade or interval.

Do not count an unobserved next review as a failure. Do not quietly discard it without showing the follow-up rate. If follow-up differs substantially by grade, the grade-specific calibration comparison may be biased by which cards came back.

This is another reason to keep the scheduled-due forecast and actual-next-review forecast distinct.

---

## 6. The proposed cut-point directions are sensible, but adjustment should be treated as a hypothesis

The direction table is easy to follow and appears internally consistent:

- Easy underperforms its prediction: reduce c1, so fewer attempts receive Easy.
- Easy exceeds its prediction: increase c1, so more attempts receive Easy.
- Hard exceeds its prediction: increase c2, so fewer attempts receive Hard.
- Hard underperforms: reduce c2, so more attempts receive Hard.

The note also correctly recognises that c1 and c2 interact through the Good band. I agree with adjusting one boundary at a time initially.

However, the observed error for a grade is not a direct estimate of the effect of changing the boundary. Moving c1 changes which attempts enter Easy, changes their scheduled intervals, and changes the future sample of outcomes. The new Easy group is not simply the old Easy group with a few records added or removed; it is a new policy.

So I would phrase the rule as:

> This pattern is evidence in favour of testing a change in this direction.

I would not phrase it as a guaranteed correction. The post-change window is a validation of the policy change, not merely a check that the old error moved toward zero.

The automatic reversal rule also needs a definition of “does not improve”. Does it mean the same grade's mean calibration error is smaller, the overall Brier score is better, or the primary exam-readiness outcome improves? These can disagree. A reversal criterion must be selected in advance and should not switch between metrics after seeing the result.

**Recommendation:** use the diagnostic to propose a direction; validate the resulting ladder on a fresh evidence block; use a predeclared primary metric for rollback.

---

## 7. The adoption outcome should include study time

The note proposes lapses per hundred reviews at fixed target retention as the primary outcome, with review load as a secondary outcome. This is understandable, but it risks rewarding a ladder that reduces lapses by making the learner spend much more time reviewing.

The learner's stated goal is exam performance, and time is a real cost. I would report at least:

- lapses per 100 reviews;
- lapses per hour of study;
- correct answers in test-style practice per hour, if the app records a sufficiently comparable measure;
- total review time and review count;
- accuracy at review.

I would not collapse these into a single score without an explicit value judgement about the trade-off. Show the outcomes together and let the learner see whether fewer lapses came at a large workload cost.

There is also a comparability issue in “at a fixed target retention”. If target retention, FSRS weights, or the mix of new and mature cards changes between windows, the comparison is not simply a ladder comparison. The trial should hold these settings stable or record and account for changes.

**Recommendation:** keep lapses per 100 reviews, but elevate lapses per hour and review workload to co-primary decision context. State that an apparent retention gain accompanied by a large increase in study time is not automatically a win.

---

## 8. The proposed alternating-block trial needs protection against carryover and time trends

The note calls an alternating block design the strongest evidence available. It is a reasonable starting idea, but a simple “one ladder for several weeks, then the other” comparison can be confounded by:

- learning and familiarity increasing over calendar time;
- syllabus/topic mix changing;
- exam proximity changing study behaviour;
- scheduler state carrying over from the first block;
- different card exposure and review frequency under the two ladders.

Because FSRS state persists, changing the ladder between blocks does not create a clean reset. A card reviewed under ladder A may continue to carry the stability and due date produced by A while being reviewed during block B.

For the first validation, I would consider a pre-registered n-of-1 comparison with randomized assignment order. If technically feasible, a card-level allocation in which matched cards remain on one ladder for the whole comparison may reduce calendar-time confounding and avoid repeatedly switching a card's policy. Match or stratify by topic/passage and initial maturity, and keep FSRS weights and target retention fixed during the trial.

That design still has limits: cards are not perfectly interchangeable, and one learner cannot provide population evidence. But it is more defensible than a simple before/after comparison. If card-level allocation is too invasive, use randomized block order, explicitly model calendar time and topic mix, and describe the result as suggestive rather than causal proof.

---

## 9. The reading-speed multiplier should not be inferred from the same outcome loop yet

The note is right to resist adding a third parameter with a small dataset. The content-based allowance assumes 20 visible characters per second, but dwell after that allowance includes more than reading speed: retrieval effort, uncertainty, option comparison, and response strategy.

If the multiplier is fitted against next-review retention, it may absorb differences in retrieval difficulty rather than identify the learner's reading speed. It could also compensate for a misspecified c1/c2 ladder, making the parameters hard to interpret.

I would keep the multiplier fixed in the first version. If personalizing reading allowance later becomes important, collect an independent signal—for example, an optional reading calibration task or a clearly defined reading-only interaction—rather than trying to identify reading speed from the same retention outcomes used to tune the grade boundaries.

The note asks whether a sample size can establish when the multiplier becomes estimable. There is no universal count that answers this. It depends on variation in content length, images, card types, repeated measurements, and how independently the reading signal can be identified from retrieval time. A simulation using the app's actual content and review-log structure would be more informative than choosing a threshold such as 100 or 500 reviews by intuition.

---

## 10. The data additions are well chosen, with a few clarifications

I support recording:

- grade source;
- ladder version;
- a snapshotted predicted-recall value;
- a version-history table with the reason and evidence behind each change.

These are small additions that make the process auditable and preserve the no-history-rewrite rule.

I would add or clarify:

1. **Prediction type and timestamp:** scheduled-due forecast versus next-review forecast, as described above.
2. **Scheduler parameter version:** identify the FSRS weights/version used for the prediction.
3. **Pair linkage:** an explicit link from a review to the next review of the same card, so the outcome pairing is reproducible.
4. **Follow-up status:** distinguish not-yet-due, overdue/not seen, suspended/removed, and observed. Do not treat these as equivalent missing values.
5. **Decision-window ID:** record which non-overlapping evidence window supported a proposed or accepted change.

The note says there is no need for a new event stream or calibration mode. I agree: these additions can be ordinary review and ladder-version fields, not a separate telemetry system.

Manual overrides should remain excluded from fitting, as the note specifies. Keep the original automatic grade and the final saved grade separately available. Otherwise the app may lose the ability to distinguish the algorithm's output from the learner's correction.

---

## 11. The coloured clock and automatic grading premises are coherent within this design

Given the settled premises, I would not reopen the decision to show the coloured clock or to keep automatic grading. The note explicitly accepts the behavioural risk and says the colour is display-only. The important implementation requirement is consistency: the displayed band must use the same ladder version as the grader.

I agree that the clock colour should not enter the calibration label. It is a product interface, not an independent measurement of retention. The review can still measure whether outcomes or dwell distributions change after the coloured clock is introduced, but a single-learner before/after result should be described cautiously because study habits and content mix may also change.

The manual override is also correctly treated as a user correction rather than a fitting label. Keep its rate as a descriptive sanity check, not as a target that the ladder is optimized to maximize or minimize.

---

## 12. Answers to the six reviewer questions

### 1. Is observed minus predicted the right estimand?

It is a good first diagnostic because it is interpretable and directly compares observed recall with the scheduler's forecast. It is not sufficient on its own. Add Brier score and prediction-range calibration as secondary diagnostics. Most importantly, pair the outcome with a prediction made for the actual next-review time, not an ambiguous prediction from the earlier review.

### 2. Is clustering by card sufficient?

Card clustering is essential but may not be sufficient where cards share a topic, passage, or source. Report distinct-card counts and use topic/passage clustering if there are enough independent groups. If there are too few clusters, do not claim a precise interval or use it to move a cut-point.

### 3. Is the diagnostic confounded beyond repair?

No, but it is descriptive rather than causal. It can show whether grades are associated with outcomes that differ from the scheduler's predictions. It cannot establish what a different cut-point would have caused. A randomized, pre-registered n-of-1 comparison is the appropriate next step if the owner wants evidence that changing the ladder improves outcomes.

### 4. Are the damping constants defensible at n = 1?

They are reasonable starting hypotheses for a simulation or proposal-only prototype, but they are not validated by the note. The rolling-window design also needs protection against repeated testing. I would first use non-overlapping evidence windows, require enough distinct cards, and propose changes for confirmation. Only then consider automatic adjustment.

### 5. Should the reading-speed multiplier be a parameter?

Not in the first version. Dwell residual is not a clean reading-speed measure. Keep the multiplier fixed unless an independent reading signal can be collected and the available content-length variation supports estimating it.

### 6. Is the adoption bar right?

Lapses per hundred reviews is useful, but incomplete. Include lapses per hour, review time, review count, and test-style accuracy where available. A lower lapse rate that requires substantially more study time may not improve the learner's overall exam preparation.

---

## 13. Recommended implementation sequence

I would make the sequence slightly more conservative than the note's final proposal:

1. **Record provenance:** grade source, ladder version, scheduler parameter version, and version history. Preserve both automatic and final grade.
2. **Define outcome pairing:** explicitly link consecutive reviews and define the next-review prediction timestamp. Track unobserved follow-ups.
3. **Report only:** show sample sizes, distinct cards, observed-versus-predicted retention, and a secondary probability score. Do not move cut-points.
4. **Validate the comparison method:** use a pre-registered n-of-1 design with fixed scheduler settings and a clear plan for topic mix, card maturity, and calendar time.
5. **Propose changes:** use non-overlapping evidence windows and show the proposed cut-point movement and supporting data. Require learner confirmation.
6. **Automate only after validation:** use bounded steps, a predeclared rollback metric, and a clear pause/reset control. Keep every prior ladder version.

The first three steps produce useful information without changing a single grade. They should be valuable even if the project ultimately decides that the data are too sparse or unstable for automatic adaptation.

---

## Final recommendation

**Approve the target and the measurement-first direction; revise the statistical and operational specification before enabling automatic cut-point changes.**

The strongest parts of the note are its exam-performance objective, its rejection of self-grades as fitting labels, its separation of ladder calibration from FSRS weight fitting, and its insistence on preserving history. The biggest unresolved issue is not the choice between mean error and Brier score. It is whether the prediction being compared to the next-review outcome is actually a forecast for that same moment, and whether the observed review pairs are representative enough to support a policy change.

I would ship provenance and reporting first, run a pre-registered single-learner validation, and keep automatic adaptation behind a later release decision. That preserves the product's settled premises while avoiding the appearance of statistical certainty that one learner's correlated, policy-dependent review history cannot provide.
