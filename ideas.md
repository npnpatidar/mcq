# Deferred ideas

Two phase-3 slices were deliberately left out of the implementation. This file
records what was discussed so the decision does not have to be reconstructed
later. Neither item is scheduled; both need an owner decision before code.

Status of the rest of phase 3: see `stats-phase3.md` and `stats.md` §11.

---

## 1. Adaptive next-test recommendation

Source: `stats-phase3.md` slice 6, `stats.md` §11.11.

### What was wanted

A card on the report (and/or the Tests tab) that answers "what should I take
next?" — a type, a length and a scope, with the evidence it used. It must never
claim that a score will improve, and it must not present a practice gain as
proof of mastery.

### What is feasible with this app's data

The app is single-user. There is no population of test takers, so there is no
way to calibrate item difficulty or discrimination against a norm group. That
rules out, for now:

- **CAT / IRT adaptive testing.** It needs calibrated item parameters and
  exposure control; this bank has neither. A one-user bank cannot supply them
  (a user's own correctness is the dependent variable the test is supposed to
  measure).
- **Staged testing** (stop when the score interval is narrow enough). The
  interval exists (`TestReport.marginPoints`), but staging a single session
  changes the plan shape and the session contract, and a stop rule calibrated
  on one user's variance is not a measurement.

What *is* feasible is a **rule-based recommendation across attempts**:

- It reads the same pure report facts already computed: score denominators,
  topic accuracy, error mix, coverage, comparable attempts, mistakes, due
  counts.
- It picks from the existing startable types and links to the existing Test
  route (type, count, scope) — no new plan shape.
- Every recommendation carries its evidence number and sample size.
- Short or legacy attempts suppress the statistical rules and keep only
  review/topic rules.

### Open questions

1. Where does it live: a card at the end of the report, a line in the Tests
   tab, or both? The report rules already exist in §11.11, so the report is the
   natural first home.
2. Does it read only the latest attempt, or the last N comparable attempts? A
   single attempt's topic accuracy is noisy; a trend across comparable attempts
   is more defensible but needs a suppression rule.
3. Should a practice attempt be allowed to recommend a random control mock? The
   rules in §11.11 say yes when the claim is about mastery; confirm the wording
   never implies the practice score itself improved.
4. Should the card remember dismissals, or is it recomputed each time from the
   report (stateless, simpler)?

### Why it is not implemented now

Per the owner's instruction, only the rule-based cross-attempt card is
plausible without population data, and it was not part of this branch's scope.
CAT/staged testing stays deferred until there is calibration data, which a
single-user app cannot produce.

---

## 2. Exam blueprint

Source: `stats-phase3.md` slice 7.

### What was wanted

Per-paper sections with counts, optional weights and optional per-section time;
a "Blueprint" test is a representative mock built from those quotas, so the
sample resembles the real exam's composition instead of the bank's.

### Why it waits

It changes the meaning of `TestPlan` (sections with their own counts and
timing versus one flat count), needs an editor for the quotas, and a paper
without a blueprint must keep working exactly as today. That is a plan-shape
change, not a selector.

### Open questions the owner has to answer

1. **Shape.** Is a blueprint one flat list of sections, or nested
   (section → category branches)? Flat is much simpler and covers most exams.
2. **Family.** Is a Blueprint attempt representative (feeds the comparable
   trend and `rep*` counters, like Random/Balanced) or practice? It is meant to
   be a mock, so representative is the likely answer, but that has consequences
   for the trend and the "comparable" key.
3. **Section pool.** Can a section draw from the whole paper, or only from
   explicit category branches? What happens when a section's pool is smaller
   than its quota — short the section, borrow from outside, or refuse to start?
   (Compare the fixed-panel rule: a fixed set never fills from outside.)
4. **Short-section behaviour.** Same question at the section level: is a
   partially filled blueprint still comparable, or does it become practice?
5. **Timing.** Is per-section time enforced in the session (a timer per
   section) or only reported? Enforcing it touches the session state machine.
6. **Weights.** What do weights mean — scoring weight per section, or just a
   target proportion of questions? If scoring, the report's `maxScore` and
   negative-marking maths change.
7. **A real example.** One actual exam blueprint (sections, counts, time,
   marking) is needed to validate the editor and the sampler. Without it the
   feature is speculative.

### Why it is not implemented now

It needs the decisions above, and it changes the plan shape that the rest of
phase 3 (and the report) depends on. Implementing it on assumptions would
likely be thrown away.
