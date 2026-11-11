# Response to the study-dwell-threshold brief

**Recommendation:** Keep response time as a *weak, optional signal* for suggesting a grade, but stop treating a fixed number of seconds as a universal measure of retrieval difficulty. The best first version is a simple, deterministic, learner-relative ladder calibrated from that learner’s **Classic-mode** self-grades, shrunk toward the existing defaults. Keep the existing correctness/guess overrides, do not use FSRS outputs as predictors, and do not show the threshold bands while the learner is answering.

This is a proposal for an offline pilot, not a claim that the resulting thresholds are already validated. The research literature supports using response time as process information, but does not establish universal Easy/Good/Hard cut-offs for this app.

## 1. Is dwell a defensible basis for an automatic grade?

**Yes, as a secondary signal under restricted conditions; no, as a stand-alone measurement of retrieval.** Dwell can contain information about fluency and effort. A quick, correct response may be evidence of fluent retrieval. A longer correct response may reflect effortful retrieval, distractor elimination or reasoning. But the same long response can also be caused by reading, visual parsing, motor time, hesitation or interruption. A short response can be a guess or a premature answer. One total clock cannot separate these processes.

This is consistent with the response-time literature. De Boeck and Jeon’s review describes response times as reflecting a mixture of automated processes (including knowledge retrieval) and controlled processing (including sequential reasoning), and stresses that the same time/accuracy pattern can have alternative explanations. It also discusses the speed–accuracy trade-off and the limits of interpreting response time as a direct measure of a particular cognitive process ([De Boeck & Jeon, 2019](https://doi.org/10.3389/fpsyg.2019.00102)). A 2026 item-level meta-analysis likewise reports heterogeneous, generally curvilinear relationships between residual response time and item discrimination rather than a simple universal linear relationship ([Gilbert et al., 2026](https://doi.org/10.1177/00131644261426972)).

There is also a construct-validity question. This app is intended to schedule knowledge retrieval, not to measure reading or motor speed. An Ofqual review of assessment timing notes that speed is not normally part of the construct in many knowledge-based examinations, although fluency may be relevant in some settings ([Holmes, 2025](https://www.gov.uk/government/publications/time-limits-and-speed-of-working-in-assessments/time-limits-and-speed-of-working-in-assessments-when-and-to-what-extent-should-speed-of-working-be-part-of-what-is-assessed)). That is a useful caution here: the app should not turn a learner’s general pace into an estimate of memory strength.

**Practical conclusion:** use dwell to distinguish likely fluent, ordinary-effort and effortful *correct* retrieval only where the timing is valid and the learner has enough calibration history. If the evidence is weak, fall back to a neutral grade or let the learner choose. An automatic grade should remain editable.

## 2. Recommended first rule

### 2.1 Keep the hard overrides

Apply these before any time calculation:

1. Empty answer, wrong exact-match answer or declared guess → **Again**.
2. Untimed review (`null` or `0`) → **Good as a neutral fallback**, not Easy.
3. A review for which the learner deliberately paused the question clock → do not infer a time grade; use the neutral fallback or ask for a manual grade.
4. If correctness or timing validity is unavailable → do not infer an Easy/Hard grade from dwell.

The third rule can be applied at runtime because the app knows when the pause control is used. The existing historical total-dwell field cannot identify which old reviews were manually paused. Those records must therefore be treated as potentially contaminated in retrospective analysis; their true on-clock exposure cannot be reconstructed from the stored total alone.

### 2.2 Keep a card-specific reading allowance, but treat it as approximate

For the first offline comparison, retain the current allowance so that the experiment changes one major component at a time:

\[
R_i=\left\lceil\frac{C_i}{20}\right\rceil+3I_i
\]

where \(C_i\) is the visible-character count and \(I_i\) is the number of visible images. This preserves the brief’s current reading model as a baseline; it does **not** endorse 20 characters/second or three seconds/image as validated values.

For a timed review, define an estimated *thinking-time residual*:

\[
X_i=\max(0,\;T_i-R_i)
\]

where \(T_i\) is the banked dwell in whole seconds. The learner-specific ladder is then applied to \(X_i\), rather than applying the same absolute thinking budgets to everyone.

Do not add arbitrary table, formula, option or mark multipliers in the first release. Those features should be tested as candidate predictors in the offline comparison. Character count is a poor proxy for mathematical or tabular parsing, but introducing several unmeasured weights at once would replace one arbitrary rule with several. If held-out results show residual error by tables or formulas, add a small, bounded feature cost and validate it separately.

### 2.3 Learn the two cut-points from Classic-mode labels

Classic mode is the best available calibration source because its grade is the learner’s own assessment and is not generated by the simplified thresholds. Do **not** train on the Simplified-mode proposal, or on an edited proposal, in the first version: seeing the proposed grade can anchor the correction. Do not use FSRS difficulty, stability, retrievability or scheduled interval as predictors.

For each learner, use timed Classic-mode reviews labelled **Easy, Good or Hard**. Exclude Again from this time-ladder fit: Again is an outcome/failure judgement, not one of the three time bands. Exclude untimed reviews, reviews with invalid or missing dwell, and inconsistent records where known correctness conflicts with the grade. Calculate the median residual time for each label:

\[
 m_E=\operatorname{median}(X\mid Easy),\quad
 m_G=\operatorname{median}(X\mid Good),\quad
 m_H=\operatorname{median}(X\mid Hard)
\]

The unshrunk boundary estimates are the midpoints between adjacent grade medians:

\[
 E^*=\frac{m_E+m_G}{2},\qquad H^*=\frac{m_G+m_H}{2}
\]

This is deliberately simple and robust to a few unusually long answers. It does not force a target percentage of reviews into any band. The observed human labels determine the boundary locations; there is no Easy quota.

**Cold-start and sparse-history rule.** Until there are at least eight usable observations in *each* of Easy, Good and Hard, use the existing prior budgets: 8 seconds for Easy and 30 seconds for Hard. Once the minimum is met, shrink the estimates toward that prior:

\[
 n_{min}=\min(n_E,n_G,n_H),\qquad
 \lambda=\frac{n_{min}}{n_{min}+20}
\]

\[
 E_p=8+\lambda(E^*-8),\qquad
 H_p=30+\lambda(H^*-30)
\]

The eight-per-grade gate and the shrinkage constant of 20 are **pilot starting values**, not literature-derived constants. They should be selected or revised by the validation procedure below. If the observed medians are not ordered as expected (\(m_E<m_G<m_H\)), or the fitted boundaries violate the minimum Good-band width, retain the prior rather than force a misleading personalisation.

For a first pilot, set a provisional minimum Good-band width of **10 seconds** in thinking-time units. Test sensitivity at 5, 10 and 22 seconds; 10 seconds is a guardrail for the pilot, not a known psychological boundary. Clamp the budgets to the app’s supported range and require \(H_p\ge E_p+10\). If the constraints cannot be met without materially distorting the estimated cut-points, use the prior for that session.

The resulting ladder is:

| Residual thinking time | Suggested grade |
|---|---|
| \(X_i<E_p\) | Easy |
| \(E_p\le X_i<H_p\) | Good |
| \(X_i\ge H_p\) | Hard |

Equivalently, the displayed card thresholds are \(R_i+E_p\) and \(R_i+H_p\). Calculate the learner’s thresholds once at session start and freeze them for that session. Re-estimate only between sessions. This is cheap, deterministic and explainable.

### 2.4 How to use state, card history and content shape

- **New cards:** a first app exposure may involve encoding rather than retrieval, but “New” does not prove the learner has never seen the material. Do not automatically call a correct New-card response Easy or Hard from time alone in the first pilot. Use Good as a neutral proposal, make the limitation clear, and let the learner change it. Evaluate New cards separately before deciding whether to add a distinct rule.
- **Learning, Review and Relearning:** initially use the same learner-level ladder, but report agreement and calibration separately by state. Add bounded state-specific offsets only if they improve chronological hold-out performance and do not worsen fairness. Do not multiply thresholds by arbitrary factors in advance.
- **Per-card history:** do not give each card its own threshold in v1. A card-specific timing baseline could normalise away genuine item difficulty or make a memorised card appear artificially easy. Use card history for analysis and, later, test a heavily shrunk card effect only when there are several independent labelled observations for that card.
- **Difficulty and leech status:** do not condition on FSRS difficulty, stability, predicted recall, interval, or leech flag. They are downstream of prior grades or scheduling history. Using them risks the feedback loop described in the brief.
- **Content shape:** keep the existing text/image allowance as the baseline. Compare table/formula/option features as separate challengers rather than adding unvalidated constants together.

## 3. Why this model rather than the other candidate directions?

| Candidate | Recommendation | Reason |
|---|---|---|
| Better reading model | Evaluate as a challenger | Needed if errors remain associated with tables, formulas or long options; do not guess several weights at once. |
| Learner-relative calibration from Classic labels | **Use first** | Directly targets the learner’s intended meaning of Easy/Good/Hard and is independent of the automatic threshold. Use shrinkage and minimum sample gates. |
| Per-card history | Defer | Sparse and likely to normalise away real item difficulty; test only as a shrunk challenger. |
| Card-state conditioning | Evaluate separately | New exposure may be encoding rather than retrieval. State multipliers need evidence and should not be arbitrary. |
| Outcome-probability curve | Offline challenger | Fit a regularised logistic/GAM curve of correctness against log dwell, allowing non-linearity. Useful for testing whether dwell predicts accuracy, but it does not directly define the learner’s subjective grade meanings. |
| Format weights | Evaluate after baseline | Add only features that improve hold-out fit and reduce content-related disagreement. |
| Simpler grading / manual grading | Keep as fallback and product alternative | When timing is invalid or calibration is weak, a neutral grade or self-grade is more honest than false precision. |
| Session-relative percentile | Do not use as the main rule | It changes the meaning of a grade with the session and can create a quota-like distribution unrelated to retrieval. |

A regularised ordinal-logistic model is a worthwhile *offline challenger* to the median-midpoint rule. It can estimate two ordered boundaries from Classic labels and include a small number of content/state terms. It should replace the simple rule only if chronological hold-out results show a material, repeatable improvement. A complex model is not automatically better when each installation may contain only a few dozen observations.

## 4. Evaluation plan using existing logs

The main test should be a **within-learner, chronological replay**, not a random row split. Random splits would allow later observations of a card or the same period of a learner’s behaviour to leak into training.

### Step 1 — Build clean analysis cohorts

Keep study-mode and mock-test responses separate. Their dwell semantics may be similar, but the context is not: mock tests have countdowns and exam pressure, while study mode shows an explanation after Check. Use Classic-mode reviews to fit the self-grade thresholds. Use Simplified-mode records with known correctness as a separate outcome-validation sample, not as training labels.

Treat `null` and `0` as untimed, not fast. Do not infer missing correctness or guess flags as false. Exclude impossible or corrupted timings using a rule fixed before comparing models. Analyse records with known manual-pause status separately if available; the brief says the historical schema does not retain pause segments, so pause contamination cannot be fully removed retrospectively. Report the percentage of rows excluded and the number of learners/cards with usable data.

### Step 2 — Compare pre-specified models

At minimum compare:

1. Current rule (8/30 plus current reading allowance).
2. The proposed Classic-calibrated, shrunk median-midpoint ladder.
3. A regularised ordinal-logistic challenger using the same basic inputs.
4. A reading-model challenger that adds table/formula/option features only where they are available.

Freeze all hyperparameters within each training fold. Use earlier Classic reviews to estimate thresholds and later Classic reviews to score agreement. Use rolling-origin evaluation where history permits. For learners with too little Classic history, report cold-start performance separately; do not hide them by evaluating only high-volume users.

### Step 3 — Measure agreement with self-grades

On held-out Classic reviews report:

- exact-match agreement;
- quadratic-weighted kappa or an equivalent ordinal agreement statistic;
- the full confusion matrix;
- per-grade precision/recall and support counts;
- the share of reviews for which the model abstains or falls back to the prior.

The confusion matrix matters. Easy-versus-Good and Good-versus-Hard disagreements have different consequences, and aggregate accuracy can conceal systematic overuse of one grade.

### Step 4 — Test whether the bands carry information about correctness

Use records with known correctness and calculate the **raw time-only band before the wrong/empty/guess override**. This is essential: if the analysis uses the final grade after wrong answers have already been forced to Again, correctness differences between grades will be partly guaranteed by the rule itself.

Compare the proportion correct across the raw Hard, Good and Easy bands, overall and by card state. Also fit a logistic or flexible smooth curve of correctness against log dwell, controlling for content shape and repeated cards where sample sizes permit. Do not assume in advance that accuracy must rise monotonically with dwell: very fast answers can be guesses, while longer times can indicate either careful reasoning or confusion. If the raw bands show no reproducible accuracy or fluency separation, response time is not earning its place as a scheduling input.

Declared guesses should be analysed separately, since the production rule already sends them to Again. Also report how many records have unknown correctness; do not treat unknown as correct.

### Step 5 — Audit fairness and content effects

Measure disagreement between the automatic grade and Classic self-grade by:

- visible-character-count decile and passage length;
- image, table and formula counts;
- number of options and number of correct options;
- category/paper where sample size permits;
- learner-level median dwell or pace proxy;
- New, Learning, Review and Relearning state.

For each group report both the disagreement rate and the direction of error (for example, automatic Hard when the learner said Good). Test whether residual disagreement still rises with learner pace after the proposed personalisation.

There is an important limit: the app does not record device scale, screen size, language comfort, assistive-technology use or disability status. Existing logs therefore cannot establish fairness for screen-reader users, large-text users, non-native speakers or people with motor impairments. A lack of correlation with median dwell is useful but is not proof of accessibility fairness. The W3C’s “Enough Time” guidance explicitly notes that some users need longer to read, locate or physically respond to content, and recommends allowing their individual response times ([W3C WCAG 2.2, Guideline 2.2](https://www.w3.org/WAI/WCAG22/Understanding/enough-time.html)). Keep an untimed/manual option available; do not make speed grading unavoidable.

### Step 6 — Test stability and sensitivity

Replay thresholds session by session. Report the median and 90th-percentile absolute change in each threshold, the influence of a single added Classic review, and bootstrap intervals for the cut-points. Repeat the analysis with the minimum sample gate and shrinkage constant varied (for example, gates of 5/8/12 per grade and shrinkage constants of 10/20/40). Test Good-band floors of 5/10/22 seconds. Choose values that improve held-out agreement without unstable threshold movement, not values that produce an attractive in-sample fit.

A learner becoming faster over months may reflect genuine mastery. Therefore, distinguish gradual long-term movement from abrupt session-to-session jumps. A rolling calibration window can be tested, but keep the production value frozen during any one session and cap how much it can move between sessions.

### Step 7 — Simulate scheduling impact, with the right caveat

Replay the candidate grades through the same FSRS-6 implementation and retention target used by the app. Compare:

- rating distribution;
- stability and difficulty trajectories;
- scheduled interval distribution;
- projected due-card counts and workload by day/week;
- lapse and leech rates, where the simulation can represent them.

Keep this simulation isolated from the actual review history. It must never rewrite past reviews or live card state. Report both relative and absolute changes from the current rule.

This is a **mechanical schedule simulation**, not proof that one policy produces better learning or retention. FSRS outputs are downstream consequences of the ratings; they are not independent evidence that the ratings were correct. Offline replay cannot fully identify the counterfactual memory outcome under a different schedule.

### Step 8 — Decide whether to run a small prospective test

Only after the offline gate is passed, consider a local, opt-in pilot. At minimum compare neutral clock display against the live colour-band clock. The threshold colours make the target visible and may change answering behaviour; the brief correctly identifies this as a measurement-reactivity problem. Existing logs cannot reveal what dwell would have been under a clock design that was never shown.

## 5. Product and accessibility recommendations

1. **Remove live green/amber/red threshold colours.** Keep a neutral elapsed-time display if useful. Show the suggested grade and a short explanation only after Check. If live colours are retained, treat them as an intervention and test them rather than assuming they are passive feedback.
2. **Keep pause as an accessibility feature, not a loophole to be punished.** If the learner manually pauses, disable the automatic time inference for that card. Do not infer that the learner retrieved quickly from a shortened on-clock total. System/background pauses can continue to be excluded as they are now.
3. **Offer an obvious manual or untimed path.** Response-time grading should be disableable, or at least bypassable per card/session. This is especially important because the logs cannot identify all accessibility needs.
4. **Explain the result after the answer.** A suitable one-sentence explanation is: “This suggestion compares your answer time, after allowing for the question’s visible content, with the pace you have previously associated with Easy, Good and Hard.” When the system is using the cold-start prior, say so: “Using the default timing guide until there is enough history to personalise it.”
5. **Show the effective thresholds in settings.** State that they are estimates and show whether the app is using the default prior or a learner-calibrated value. Do not imply that a value derived from a small sample is a precise measurement.

## 6. Direct answers to the brief’s questions

| Question area | Recommendation |
|---|---|
| Validity of dwell | A useful auxiliary signal for correct, timed answers, not a direct or complete measure of retrieval difficulty. Separate rapid guessing and incorrectness with the existing overrides. |
| Reading versus retrieval | Keep the existing additive allowance for the first comparison; calibrate the remaining pace to the learner. Total dwell cannot fully identify the two components. |
| New versus review | Analyse separately. Do not assume a New card is necessarily new knowledge; use a neutral fallback until a separate rule is validated. |
| Functional form | Start with additive reading cost plus learner-relative residual-time thresholds. Compare a regularised ordinal model and a flexible correctness curve offline. |
| Pooling level | Learner-level calibration first, shrunk to the fixed prior. Do not use per-card thresholds in v1. Consider category/state effects only with adequate data and held-out benefit. |
| Pause control | If manually paused, mark timing invalid at runtime and use a neutral/manual grade. Historical totals cannot be repaired without a pause indicator. |
| Self-grade bias | Classic labels are the best available target, but not an objective gold standard. Use chronological hold-out, test against correctness separately, and exclude Simplified-mode suggestions from training. |
| FSRS feedback loop | Do not use FSRS difficulty, stability, retrievability or interval as threshold inputs. Freeze each session’s thresholds. |
| Fairness | Learner calibration may reduce pace bias but cannot prove accessibility fairness. Keep manual/untimed use available; existing logs lack the metadata needed to validate all affected groups. |
| Minimum sample | Treat 8 examples in each of Easy/Good/Hard as a provisional activation gate, with shrinkage. Select the gate and shrinkage strength from stability and hold-out tests. |
| Evaluation | Chronological replay for Classic-grade agreement; raw-band correctness calibration; fairness, stability and schedule simulation; then a small local experiment for display effects. |
| Drift | Update only between sessions, slowly, using new Classic labels; distinguish long-term learning from short-term noise. |
| Live clock | Do not show grade-coloured bands while answering. Test colour versus neutral display if the team wants evidence. |

## 7. Important limitations and decision gate

The proposed model cannot recover information the app does not store. In particular, one whole-second total cannot separate reading, retrieval, re-reading, motor action and hesitation; there is no record of pause segments, reading comfort, screen scale or assistive technology. Classic labels are a useful learner-specific criterion, but they are still self-reports and may be inconsistent. The proposed medians are consequently a pragmatic baseline, not a validated cognitive measurement scale.

There is also a data-governance constraint. The brief says installations have no server, account or cross-user data. A genuinely cross-learner research evaluation would require an explicit, consented way to export or aggregate data. Without that, the evaluation can run locally per installation and report aggregate summaries only if a separate privacy-approved process is introduced. Do not assume that per-learner logs can be pooled centrally.

**Suggested go/no-go gate:** do not ship the personalised rule as the default until it beats the current rule on chronological held-out Classic-grade agreement, does not worsen content-length/pace disparities, produces stable thresholds, and yields an acceptable FSRS workload simulation. If it does not pass, keep the existing rule as a configurable heuristic or make self-grading the default. If it passes, ship it as an editable suggestion with neutral clock display and a visible cold-start explanation.

## References

- De Boeck, P., & Jeon, M. (2019). *An Overview of Models for Response Times and Processes in Cognitive Tests*. Frontiers in Psychology, 10, 102. https://doi.org/10.3389/fpsyg.2019.00102
- Gilbert, J. B., et al. (2026). *Conditional Dependencies Between Response Time and Item Discrimination: An Item-Level Meta-Analysis*. Educational and Psychological Measurement. First published online 17 March 2026. https://doi.org/10.1177/00131644261426972
- Holmes, S. (2025). *Time limits and speed of working in assessments: When, and to what extent, should speed of working be part of what is assessed?* Ofqual Research Report 25/7267/2. https://www.gov.uk/government/publications/time-limits-and-speed-of-working-in-assessments/time-limits-and-speed-of-working-in-assessments-when-and-to-what-extent-should-speed-of-working-be-part-of-what-is-assessed
- World Wide Web Consortium (W3C). *Understanding WCAG 2.2: Guideline 2.2 — Enough Time*. Updated 15 July 2025. https://www.w3.org/WAI/WCAG22/Understanding/enough-time.html
