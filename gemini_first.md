# Automated Learner-Relative Dwell Calibration for Spaced Repetition in High-Stakes Exam Optimization

## 1. Architectural Foundations and Algorithmic Integration

Spaced repetition systems optimized for high-stakes examinations rely on scheduling algorithms that translate learner performance into optimal review intervals. The core scheduling engine utilized in modern memory architectures is the Free Spaced Repetition Scheduler (FSRS), an advanced model based on the Difficulty, Stability, Retrievability (DSR) paradigm. FSRS parameterizes memory into three fundamental state variables: Retrievability (R), defined as the probability of successful recall at time t; Stability (S), defined as the duration in days required for R to decay from 100% to 90%; and Difficulty (D), which measures the inherent complexity of the item and its resistance to stability increases.

The mathematical formulation governing memory retrievability decay in FSRS-4.5 and FSRS-5 is expressed as a power function:

$$R(t, S) = \left(1 + FACTOR \cdot \frac{t}{S}\right)^{DECAY}$$

where DECAY = -0.5 and FACTOR = 19/81, yielding the explicit retrievability decay equation:

$$R(t, S) = \left(1 + \frac{19}{81} \cdot \frac{t}{S}\right)^{-0.5}$$

To maintain a desired target retention $r \in [0.75, 0.97]$ (defaulting to $r = 0.90$), FSRS calculates the exact due interval $I(r, S)$ via the inverse retrievability transformation:

$$I(r, S) = \frac{81}{19} \cdot S \cdot \left(r^{-2} - 1\right)$$

When $r = 0.90$, $I(0.90, S) = S$, aligning the review interval directly with the defined stability period.

The end-to-end computational pipeline operates as a sequential control system. Raw active on-screen dwell time is recorded by the device host, from which an item-specific reading allowance is subtracted to yield residual cognitive dwell time. This residual metric is evaluated against two adjustable cut-point thresholds ($c_1$ and $c_2$) within the dwell ladder engine to automatically assign one of four discrete ratings ($G \in \{1, 2, 3, 4\}$, corresponding to Again, Hard, Good, and Easy).

The assigned grade G is then passed downstream to the FSRS scheduler, where initial item stability $S_0(G)$ is initialized directly from the first rating using weight parameters $w_0, w_1, w_2, w_3$:

$$S_0(G) = w_{G-1}$$

Subsequent updates to memory stability following a successful recall review ($S'_r$) are governed by the non-linear stabilization function:

$$S'_r(D, S, R, G) = S \cdot \left( e^{w_8} \cdot (11 - D) \cdot S^{-w_9} \cdot \left(e^{w_{10} \cdot (1 - R)} - 1\right) \cdot w_{15}^{(\text{if } G=2)} \cdot w_{16}^{(\text{if } G=4)} + 1 \right)$$

Post-lapse stability following a failed review ($G = 1$) is calculated via $S'_f$:

$$S'_f(D, S, R) = w_{11} \cdot D^{-w_{12}} \cdot \left((S + 1)^{w_{13}} - 1\right) \cdot e^{w_{14} \cdot (1 - R)}$$

Item difficulty D updates dynamically across successive reviews. In FSRS-5, initial difficulty $D_0(G)$ and updated difficulty $D''$ are computed with a linear damping factor and mean reversion targeted toward $D_0(4)$ (Easy):

$$D_0(G) = w_4 - e^{w_5 \cdot (G - 1)} + 1$$

$$\Delta D(G) = -w_6 \cdot (G - 3)$$

$$D' = D + \Delta D \cdot \frac{10 - D}{9}$$

$$D'' = w_7 \cdot D_0(4) + (1 - w_7) \cdot D'$$

Because FSRS relies entirely on the grade G to project stability trajectories, the upstream process that assigns G determines the temporal distribution of all future reviews. In fully automated grading architectures, manual entry of subjective effort is replaced by automated behavioral measurement based on active on-screen dwell time.

Active dwell time $T_{\text{dwell}}$ is recorded strictly using the host device's monotonic clock, measuring visible viewport presence while an item remains answerable. Off-screen, backgrounded, screen-off, or explicitly paused states are strictly excluded from $T_{\text{dwell}}$. To account for variable item lengths and visual assets, a deterministic content reading allowance $T_{\text{read}}$ is subtracted from active dwell:

$$T_{\text{read}} = \left\lceil \frac{N_{\text{char}}}{20} \right\rceil + 3 \cdot N_{\text{img}}$$

where $N_{\text{char}}$ represents the total visible character count across the question stem, answer choices, and passage context, and $N_{\text{img}}$ represents the number of embedded images. The residual dwell time $T_{\text{res}}$ represents active cognitive processing time beyond initial reading:

$$T_{\text{res}} = \max(0, T_{\text{dwell}} - T_{\text{read}})$$

The dwell ladder translates $T_{\text{res}}$ into discrete grades G via two cut-point parameters: lower boundary $c_1$ and upper boundary $c_2$. The baseline default configuration initializes $c_1 = 8$ seconds and $c_2 = 30$ seconds.

| Categorical Grade (G) | Operational logic and input criteria | Dwell threshold condition | Downstream FSRS state impact |
|---|---|---|---|
| G = 1 (Again) | Answer incorrect, empty submission, or explicit guess declared prior to checking. | N/A (dwell time bypassed). | Triggers post-lapse stability $S'_f$; increases difficulty D towards maximum. |
| G = 4 (Easy) | Answer correct; rapid retrieval execution indicating minimal cognitive friction. | $T_{\text{res}} < c_1$ | Multiplies stability increase by factor $w_{16}$; accelerates interval growth. |
| G = 3 (Good) | Answer correct; nominal retrieval effort within standard cognitive latency bounds. | $c_1 \le T_{\text{res}} < c_2$ | Applies baseline stabilization factor; maintains steady interval progression. |
| G = 2 (Hard) | Answer correct; prolonged response time indicating hesitation or high difficulty. | $T_{\text{res}} \ge c_2$ | Attenuates stability increase by factor $w_{15}$; compresses next review interval. |

## 2. The Calibration Target: Empirical vs. Self-Reported Retention

Attempting to calibrate dwell boundaries $c_1$ and $c_2$ by aligning automatic grades with subjective user self-reports introduces structural biases that undermine long-term retention. Subjective ratings of felt difficulty correlate weakly with long-term memory stability. Learners routinely mistake retrieval fluency during initial practice for long-term storage capacity, resulting in overconfident grading of familiar-looking content.

Furthermore, self-reports are subject to interface anchoring bias. The presence of an active visual clock or proposed grade widget alters user perception, prompting individuals to conform their self-evaluations to displayed timer thresholds rather than internal cognitive states. Manual grade overrides also present severe selective sampling bias. Because overrides are generated exclusively when a learner perceives an algorithmic failure, fitting cut-points to override events skews the sample, pulling the median dwell of observed Easy responses upward and Hard responses downward, artificially narrowing the Good category band.

To overcome these structural limitations, calibration must be anchored directly to empirical recall outcomes measured at subsequent reviews rather than unadjusted raw recall rates. Comparing unscaled raw recall rates across grades creates false conclusions; for instance, items marked Easy naturally experience longer scheduled intervals (e.g., 90 days), resulting in lower raw recall accuracy at review time compared to Hard items scheduled on short intervals (e.g., 10 days).

Instead, proper calibration minimizes grade-specific calibration error relative to the scheduler's predicted retrievability baseline. Let $K_G$ denote the set of paired sequential reviews $(k, k+1)$ for a given item where review $k$ was assigned grade G. Let $Y_{i, k+1} \in \{0, 1\}$ represent the observed binary outcome (1 for correct, 0 for incorrect) at review $k+1$, and let $\hat{R}_{i, k}$ represent the snapshotted retrievability predicted by FSRS for review $k+1$ at the time review $k$ was executed. The grade-specific calibration error $\Delta_G$ is formulated as:

$$\Delta_G = \frac{1}{|K_G|} \sum_{(i,k) \in K_G} \left( Y_{i, k+1} - \hat{R}_{i, k} \right)$$

Evaluating observed success $Y_{i, k+1}$ against predicted retrievability $\hat{R}_{i, k}$ isolates whether a grade assignment was pessimistic or overly generous while controlling for interval length. A positive calibration error ($\Delta_G > 0$) indicates that items receiving grade G retained memory more effectively than the scheduler predicted, demonstrating that the grade was excessively harsh. Conversely, a negative calibration error ($\Delta_G < 0$) signifies that items carrying label G decayed faster than predicted, indicating that the grade was overly generous.

| Observed diagnostic pattern | Statistical and cognitive interpretation | Systemic calibration adjustment |
|---|---|---|
| $\Delta_{\text{Easy}} < -0.05$ | Easy grade awarded to unsecure material; intervals over-expanded. | Decrease $c_1$: constricts Easy dwell window; requires faster execution. |
| $\Delta_{\text{Easy}} > +0.05$ | Easy grade withheld from secure material; intervals under-expanded. | Increase $c_1$: expands Easy dwell window; allows slower execution. |
| $\Delta_{\text{Hard}} > +0.05$ | Hard grade awarded to secure material; intervals excessively compressed. | Increase $c_2$: reduces Hard grade assignments; expands Good range. |
| $\Delta_{\text{Hard}} < -0.05$ | Hard grade insufficient to protect retention; material decaying prematurely. | Decrease $c_2$: increases Hard grade assignments; compresses intervals. |
| $\Delta_{\text{Good}} \neq 0$, neighbours neutral | Good intermediate band absorbing systemic timing misalignment. | Shift boundary adjacent to the neighbour exhibiting higher absolute miscalibration. |
| $\Delta_{\text{Easy}} \approx \Delta_{\text{Good}} \approx \Delta_{\text{Hard}} \neq 0$ | Global baseline drift across all operational grades. | Freeze dwell calibration: re-optimize global FSRS parameters or target retention r. |
| $\Delta_{\text{Easy}} \approx \Delta_{\text{Good}}$ | Dwell threshold fails to differentiate recall probabilities between grades. | Widen Good band: avoid artificial split; merge boundary toward baseline. |

## 3. Statistical Inference in Single-Learner Systems: GEE and Cluster Bootstrapping

Evaluating calibration parameters within a single-learner architecture introduces a primary statistical constraint: repeated reviews of the same flashcard over time generate non-independent, intra-item correlated binary observations. Treating individual reviews as independent Bernoulli trials systematically underestimates sample variance, yielding artificially narrow confidence intervals that trigger spurious adjustments of $c_1$ and $c_2$.

To maintain robust inference without cross-user population models, the calibration engine models longitudinal review outcomes within a Generalized Estimating Equations (GEE) framework. Let $Y_{ij}$ represent the binary recall result for card i at review iteration j, where $i = 1, \dots, N$ indexes unique items, and $j = 1, \dots, n_i$ indexes repeated reviews for card i. The marginal expectation of the outcome is modeled via a logit link function:

$$\text{logit}(\mu_{ij}) = \text{logit}(\mathbb{E}[Y_{ij} \mid X_{ij}]) = X_{ij}^T \beta$$

where $X_{ij}$ represents covariate terms including the assigned grade indicator and snapshotted retrievability $\hat{R}_{ij}$. The marginal covariance matrix $V_i$ for card i is formulated as:

$$V_i = A_i^{1/2} R_i(\alpha) A_i^{1/2}$$

where $A_i = \text{diag}(v(\mu_{i1}), \dots, v(\mu_{in_i}))$ contains marginal variance functions $v(\mu_{ij}) = \mu_{ij}(1 - \mu_{ij})$, and $R_i(\alpha)$ defines a working correlation matrix (such as an exchangeable or first-order autoregressive AR-1 structure) parameterizing intra-card dependency across time. Parameter vector $\beta$ is solved via the generalized estimating score equations, with $D_i = \frac{\partial \mu_i}{\partial \beta}$. Robust variance estimation relies on the Huber-White sandwich estimator:

$$\text{Var}(\hat{\beta}) = \left( \sum_{i=1}^N D_i^T V_i^{-1} D_i \right)^{-1} \left( \sum_{i=1}^N D_i^T V_i^{-1} \text{Cov}(Y_i) V_i^{-1} D_i \right) \left( \sum_{i=1}^N D_i^T V_i^{-1} D_i \right)^{-1}$$

Because asymptotic GEE properties can break down when the effective number of active clusters N is small, variance estimation for the mean calibration error $\Delta_G$ employs an exchangeably weighted cluster bootstrap centered at the card level. In this framework, entire card review histories — rather than isolated review events — serve as the discrete resampling units.

Given an evaluation dataset containing N unique cards and $M = \sum_{i=1}^N n_i$ total paired reviews, the cluster bootstrap draws N card clusters with replacement from the unique card set $\{1, 2, \dots, N\}$. A bootstrap sample $B_b$ is constructed by pooling all longitudinal reviews associated with the sampled card clusters, from which the bootstrap estimate of calibration error $\Delta_{G, b}^*$ is calculated:

$$\Delta_{G, b}^* = \frac{1}{|K_{G, b}^*|} \sum_{(i,k) \in K_{G, b}^*} \left( Y_{i, k+1}^* - \hat{R}_{i, k}^* \right)$$

This process is repeated across $B = 2{,}000$ iterations to construct an empirical distribution $\hat{F}^*(\Delta_G^*)$, yielding non-parametric percentile 90% confidence intervals $[CI_{0.05}, CI_{0.95}]$ derived directly from the 5th and 95th percentiles. An adjustment to threshold $c_1$ or $c_2$ is authorized if and only if the cluster-bootstrapped confidence interval $[CI_{0.05}, CI_{0.95}]$ strictly excludes zero, ensuring that cut-point shifts are never triggered by intra-item variance.

## 4. Psychometric Response-Time Integration: The van der Linden Framework

The subtraction of a basic reading allowance (20 char/s + 3 s/img) serves as an initial operational heuristic. However, psychometric theory demonstrates that response times during testing reflect complex interactions between item-level properties and individual latent traits. Modern item response theory (IRT) parameterizes response times using van der Linden's hierarchical lognormal framework.

Under the van der Linden model, the logarithmic response time $\ln T_{ij}$ of learner j on item i is decomposed into structural components:

$$\ln T_{ij} = \beta_i - \tau_j + \varepsilon_{ij}, \quad \varepsilon_{ij} \sim \mathcal{N}(0, \alpha_i^{-2})$$

where $\beta_i \in \mathbb{R}$ represents item time-intensity (quantifying the intrinsic duration required to process item i), $\tau_j \in \mathbb{R}$ represents person speed (reflecting individual operational velocity), and $\alpha_i > 0$ represents item time-discrimination (measuring how sharply item i differentiates fast from slow cognitive execution). The residual error $\varepsilon_{ij}$ follows a normal distribution with item-specific variance $\sigma_i^2 = \alpha_i^{-2}$.

This framework highlights why unadjusted raw dwell thresholds ($c_1$, $c_2$) fail. An item with high time-intensity $\beta_i$ (such as a multi-step clinical vignette) requires elevated processing time even when memory stability S is high. Evaluating long response durations on high-$\beta_i$ items without card-level variance adjustment misinterprets item complexity as memory weakness, assigning a Hard grade (G = 2) and prematurely compressing review intervals.

Conversely, items with low time-intensity $\beta_i$ produce fast responses despite weak memory stability, improperly earning Easy grades (G = 4) that over-expand review intervals. High item time-discrimination $\alpha_i$ further skews residual dwell by magnifying response time differences between cognitive processing modes. By incorporating card-clustered GEE variance estimates, the dwell ladder ensures that cut-point shifts respond to systematic miscalibration across the learner's history rather than single-item psychometric anomalies.

## 5. Closed-Loop Adaptation Dynamics and Control Infrastructure

To maintain operational stability, calibration operates via a two-tier adaptive control loop. Tier 1 maintains static baseline prior thresholds ($c_1 = 8$ s, $c_2 = 30$ s) when local data is sparse. Tier 2 executes damped proportional adaptations once minimum statistical evidence thresholds are met within a 500-pair sliding window.

If evidence criteria are not satisfied — such as having fewer than 30 paired reviews for a grade or fewer than 10 outcomes in either binary recall class — the system maintains current cut-points. When criteria are met, the adaptive engine evaluates calibration error and executes bounded adjustments.

The updating formulas for thresholds $c_1$ and $c_2$ are defined as:

$$\Delta c_1 = -\text{sign}(\Delta_{\text{Easy}}) \cdot \max\left(1, \lfloor 0.15 \cdot c_1 \rceil\right) \quad \text{if } |\Delta_{\text{Easy}}| > 0.05 \text{ and } 0 \notin CI_{90\%}(\Delta_{\text{Easy}})$$

$$\Delta c_2 = \text{sign}(\Delta_{\text{Hard}}) \cdot \max\left(1, \lfloor 0.15 \cdot c_2 \rceil\right) \quad \text{if } |\Delta_{\text{Hard}}| > 0.05 \text{ and } 0 \notin CI_{90\%}(\Delta_{\text{Hard}})$$

| System parameter | Parameter value | Operational purpose and stability constraint |
|---|---|---|
| Sliding window size | 500 paired outcomes | Restricts estimation to recent historical performance; capped at a 90-day maximum horizon. |
| Minimum grade evidence | $n_G \ge 30$ pairs | Requires at least 30 paired reviews per grade, with $\ge 10$ outcomes in each binary recall class (Y=0, Y=1). |
| Action threshold | $\lvert \Delta_G \rvert > 0.05$ | Requires absolute calibration error to exceed 5 percentage points before initiating threshold changes. |
| Step-size attenuation | 15% of current value | Restricts updates to 15% step changes (minimum 1 second) to prevent control loop oscillation. |
| Single-cut priority | 1 boundary per step | Adjusts only the most miscalibrated boundary per cycle; freezes engine for a full window before adjusting the secondary cut-point. |
| Physical parameter bounds | $c_1 \in [3, 60]$ s; $c_2 \in [c_1+5, 300]$ s | Enforces structural constraints to keep clock bands physically meaningful and prevent invalid configurations. |
| Scheduler concurrency | Paused during FSRS refit | Freezes dwell adaptations during FSRS weight optimizations to prevent confounded feedback loops. |
| Automatic reversal | Mandatory rollback | Restores prior cut-points automatically if calibration error increases over the subsequent monitoring window. |

A critical structural challenge in closed-loop calibration is policy-induced feedback coupling. Because interval lengths are determined by assigned grades, altering dwell boundaries shifts the distribution of intervals presented to the learner.

For example, widening $c_1$ causes more reviews to be marked Easy, expanding subsequent intervals and lowering raw future recall rates. Left unconstrained, this effect can create cyclic feedback loops where the system continuously shifts boundaries. To break these feedback cycles, the adaptation engine implements hysteresis freezing (requiring a full evaluation window between adjustments) and uses predicted retrievability $\hat{R}$ as a baseline benchmark, neutralizing interval-expansion drift.

## 6. Causal Validation via N-of-1 Alternating Block Crossover Trials

Observational tracking of calibration metrics cannot definitively confirm whether threshold adjustments cause improved learner retention or merely reflect changes in study material difficulty. Establishing true causal efficacy requires an N-of-1 alternating block crossover design.

In the N-of-1 crossover framework, the learner's study history is partitioned into alternating two-week evaluation blocks where candidate ladder $\mathcal{L}_{\text{cand}} = (c_1', c_2')$ and prior ladder $\mathcal{L}_{\text{prior}} = (c_1^0, c_2^0)$ are systematically alternated. Flashcard items are randomly assigned to treatment tracks to prevent topic confounding. Outcome evaluation occurs at Day 90 across all completed blocks.

The primary outcome metric is the lapse rate per hundred reviews ($L_{100}$) measured at a fixed target retention $r = 0.90$:

$$L_{100} = 100 \cdot \frac{\sum_{i=1}^N \mathbb{I}(G_{i, k+1} = 1)}{\sum_{i=1}^N \mathbb{I}(G_{i, k} \in \{2, 3, 4\})}$$

The candidate ladder $\mathcal{L}_{\text{cand}}$ is permanently adopted if and only if it satisfies the adoption criterion:

$$\frac{L_{100}(\mathcal{L}_{\text{cand}}) - L_{100}(\mathcal{L}_{\text{prior}})}{L_{100}(\mathcal{L}_{\text{prior}})} \le -0.10 \quad \text{and} \quad 0 \notin CI_{90\%}\left( \Delta L_{100} \right)$$

Secondary and tertiary outcomes evaluate total study workload efficiency and manual override frequency.

| Metric category | Target indicator | Formula | Operational adoption requirement |
|---|---|---|---|
| Primary outcome | Lapses per hundred reviews ($L_{100}$) | See above | Statistically significant reduction $\ge 10\%$ relative to prior ladder. |
| Secondary workload | Review Burden Index (RBI) | $\text{RBI} = \frac{\text{Total Time (hours)}}{\text{Graduated Cards}}$ | Non-inferiority ($RBI_{\text{cand}} \le 1.05 \cdot RBI_{\text{prior}}$); prevents retention gains driven by excessive study time. |
| Tertiary ergonomics | Manual Override Rate (MOR) | $\text{MOR} = \frac{\text{Manual Grade Changes}}{\text{Total Automatic Reviews}}$ | Reduction in manual overrides ($MOR_{\text{cand}} < MOR_{\text{prior}}$); indicates improved alignment with user perceptions. |

## 7. Storage Architecture, System Provenance, and Interface Design

Implementing auditable calibration without altering historical review data requires targeted extensions to the relational schema. Modifying past grade logs is strictly prohibited; all historical reviews retain the precise ladder configuration under which they were originally evaluated.

The storage architecture decouples active scheduling states from versioned calibration histories. The review logging subsystem records the exact value of predicted retrievability $\hat{R}$ at the time of review execution. Snapshotting $\hat{R}$ prevents retrospective drift: re-optimizing global FSRS weights would otherwise recalculate historical retrievability values, invalidating past calibration error calculations.

**ladder_versions**

| Attribute column | Data type and constraints | Domain function and structural rule |
|---|---|---|
| version_id | INTEGER PRIMARY KEY | Unique identifier for versioned ladder rules. |
| c1_seconds | REAL NOT NULL | Operational $c_1$ cut-point threshold. |
| c2_seconds | REAL NOT NULL | Operational $c_2$ cut-point threshold. |
| trigger_reason | TEXT CHECK(...) | Transition source (PRIOR, ADJUSTMENT, MANUAL, ROLLBACK). |
| sample_size_n | INTEGER NOT NULL | Count of paired outcomes supporting version change. |
| replaced_version_id | INTEGER FK | Reference to preceding ladder state for audit trails. |

**review_provenance**

| Attribute column | Data type and constraints | Domain function and structural rule |
|---|---|---|
| review_id | INTEGER PRIMARY KEY | Unique review event identifier. |
| card_id | INTEGER NOT NULL | Foreign key referencing target flashcard. |
| active_dwell_ms | INTEGER NOT NULL | Recorded monotonic active visible viewport time. |
| residual_dwell_ms | INTEGER NOT NULL | Net processing duration after reading allowance subtraction. |
| grade_assigned | INTEGER CHECK(1..4) | Final grade output passed to FSRS engine. |
| grade_source | TEXT CHECK(...) | Classification (AUTOMATIC, HUMAN_OVERRIDE, HUMAN_EDIT). |
| ladder_version_id | INTEGER FK | Relational binding to ladder_versions table. |
| snapshotted_predicted_r | REAL NOT NULL | Immutably stored FSRS retrievability prediction. |

User interface elements remain unobtrusive to minimize behavioral bias. Displaying active prompts or adaptive notifications encourages timer-watching behaviors that alter natural response rates.

The user interface exposes calibration controls through three quiet configuration surfaces:

- Settings Status Display: Displays active cut-points ($c_1$, $c_2$), current adaptation state (Active/Paused), and the timestamp of the last calibration update.
- Performance Diagnostic Panel: Visualizes empirical retention against predicted retrievability across grades, displaying cluster-bootstrapped confidence bounds and sample sizes.
- Control Toggle & Manual Override: Provides a manual switch to pause automatic calibration and a one-tap button to reset thresholds to default prior values ($c_1 = 8$ s, $c_2 = 30$ s).

## 8. Critical Theoretical Evaluation and Nuanced Conclusions

Evaluating grade calibration currently relies on mean calibration error $\Delta_G = \bar{Y}_G - \bar{\hat{R}}_G$. While computationally efficient, this metric can obscure variance anomalies if overconfident predictions cancel out underconfident ones within the same grade bin. Replacing $\Delta_G$ with a strictly proper scoring rule — specifically the bin-decomposed Brier Score or Logarithmic Loss — provides improved diagnostic separation:

$$\text{BS}_G = \frac{1}{|K_G|} \sum_{(i,k) \in K_G} \left( Y_{i, k+1} - \hat{R}_{i, k} \right)^2$$

Optimizing cut-points to minimize $\text{BS}_G$ separates miscalibration stemming from incorrect dwell boundaries from miscalibration caused by inaccurate global FSRS stability predictions.

Single-level clustering at the card level accounts for intra-card correlation across longitudinal reviews. However, flashcards nested within complex domain topics (e.g., organic chemistry mechanisms vs. basic vocabulary) exhibit shared intra-topic correlation. Ignoring topic-level clustering can underestimate variance bounds in specialized study modules. Upgrading the statistical framework to a two-level hierarchical GEE model — nesting repeated reviews within cards, and cards within conceptual topics — ensures valid confidence intervals across diverse subject matter.

The reading allowance model uses a fixed global speed of 20 characters/second. Psychometric modeling demonstrates that reading speed varies across individuals and content types. Introducing a learner-specific reading speed multiplier $\gamma$ modifies the reading allowance calculation:

$$T_{\text{read}} = \left\lceil \frac{N_{\text{char}}}{20 \cdot \gamma} \right\rceil + 3 \cdot N_{\text{img}}$$

While introducing $\gamma$ improves residual dwell accuracy for non-native readers or dense technical texts, estimating $\gamma$ on small sample sizes (n < 200 reviews) risks fitting noise. Parameter $\gamma$ should remain fixed at $\gamma = 1.0$ until a learner completes at least 500 reviews across diverse item lengths.

Finally, the current adoption criterion requires a $\ge 10\%$ reduction in lapses per hundred reviews ($L_{100}$). However, this metric ignores the study time required to achieve that reduction. An adaptation that reduces lapses by 10% but increases total daily study duration by 40% imposes a high efficiency cost on exam candidates. The primary adoption criterion should be updated to a time-normalized efficacy metric, evaluating Lapses Prevented Per Hour of Active Study Time (LPH):

$$LPH = \frac{\text{Lapses}_{\text{prior}} - \text{Lapses}_{\text{cand}}}{\text{Total Active Study Hours}}$$

Adopting candidate threshold adjustments requires $LPH \ge 1.5$, ensuring that ladder adaptations yield practical study-time savings for exam preparation.

## Works cited

1. FSRS-5: the modern spaced repetition algorithm - Diane, https://www.diane.app/en/outils/fsrs
2. The Algorithm · open-spaced-repetition/awesome-fsrs Wiki - GitHub, https://github.com/open-spaced-repetition/awesome-fsrs/wiki/The-Algorithm
3. ABC of FSRS · open-spaced-repetition/awesome-fsrs Wiki - GitHub, https://github.com/open-spaced-repetition/awesome-fsrs/wiki/ABC-of-FSRS
4. Generalized Estimating Equations (GEE) Approach for Clustered Data, https://openprairie.sdstate.edu/cgi/viewcontent.cgi?article=1227&context=datascience_symposium
5. Getting Started with Generalized Estimating Equations | UVA Library, https://library.virginia.edu/data/articles/getting-started-with-generalized-estimating-equations
6. The Generalised Estimating Equations: An Annotated Bibliography, http://yaroslavvb.com/papers/ziegler-generalized.pdf
7. Using Generalized Estimating Equations for Longitudinal Data, https://www.nematilab.info/bmijc/assets/160330_slides.pdf
8. Generalized Estimating Equations - IBM, https://www.ibm.com/docs/en/spss-statistics/30.0.0?topic=statistics-generalized-estimating-equations
9. The cluster bootstrap consistency in generalized estimating equations, https://www.researchgate.net/publication/267670820_The_cluster_bootstrap_consistency_in_generalized_estimating_equations
10. Run van der Linden Lognormal Response-Time Model Calculator, https://metricgate.com/calculator/van-der-linden-lognormal-rt
11. A Lognormal Model for Response Times on Test Items, https://www.semanticscholar.org/paper/9bc1851c10cabbcb28b1c95f1b2d22b14d5102ef
12. Handbook of Item Response Theory: Volume One, Models, https://api.pageplace.de/preview/DT0400.9781482282474_A34089181/preview-9781482282474_A34089181.pdf
13. Crossover Study Design Overview | PDF - Scribd, https://www.scribd.com/document/901869766/Crossover-Design
14. Design and Analysis of N-Of-1 Trials That Incorporate Sequential Features, https://pubmed.ncbi.nlm.nih.gov/40690192/
15. Case studies, single-subject research, and N of 1 randomized trials, https://pubmed.ncbi.nlm.nih.gov/10088595/
