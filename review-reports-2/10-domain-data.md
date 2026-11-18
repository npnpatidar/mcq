# Round-2 review — domain & data integrity

**Branch:** `feature/review-round-2` @ `fbc8e15` (= main, release 0.0.61). Read-only; no product/test/docs changed.
**Scope:** `domain/` (Fsrs, DayBoundary, Study, signals, FsrsReplay), `data/local/` (entities, DAOs, converters, migrations 3→28, schema 28), `data/repository/`, `data/backup/` & `data/export/` where integrity is at stake; transaction boundaries, cross-paper move, backup/replay consistency, CAS rebuild, review-log invariants.
**Method:** old `review-reports/15-c-domain.md` & `16-d-data-integrity.md` treated as **claims**, re-derived from current source + `git diff 0.0.53..HEAD`. Algorithm claims checked against upstream fsrs-rs v7.0.0 ([model_v7.rs](https://raw.githubusercontent.com/open-spaced-repetition/fsrs-rs/v7.0.0/src/model_v7.rs), [parameter_clipper_v7.rs](https://raw.githubusercontent.com/open-spaced-repetition/fsrs-rs/v7.0.0/src/parameter_clipper_v7.rs), [inference_v7.rs](https://raw.githubusercontent.com/open-spaced-repetition/fsrs-rs/v7.0.0/src/inference_v7.rs)) and ts-fsrs FSRS-7; Room claims against official docs. Anki day semantics checked against [rslib `browser_table.rs` `due_time()`](https://github.com/ankitects/anki/blob/main/rslib/src/browser_table.rs).

## Verdict

The core math is upstream-correct and the 0.0.54–0.0.61 rewrites genuinely fixed the DST and stale-cache claims. No High/Critical defect. Six residual items remain: one real but Low user-visible inconsistency (Anki leech threshold), three crafted/latent data-integrity cases carried over from earlier audits, one untested DST asymmetry in the Anki day mapping, and one harmless clip divergence from upstream. All need an owner call or a test, not an urgent code fix.

## Findings

**D1 — Low — Anki import ignores the configured leech threshold.** `AnkiScheduling.kt:174,284` sets `leech = lapses >= 8` and `fromAnki` takes no config; the live scheduler uses `config.leechThreshold` (`Fsrs.kt:195`), which the learner can change (`StudySchedulerSection.kt:504-507`, range 1..100 in `SpacedRepetition.kt:266`). A deck with, say, 5 lapses imports as non-leech under 8, then flips to leech on the first study when the threshold is 3. *Why missed:* old C3 named it; no fix batch touched `AnkiScheduling`, and `AnkiSchedulingTest.eightLapsesMakesALeech` (`:214-228`) pins 8 as if intentional. *Fix:* pass the configured threshold into `fromAnki` (recompute `leech` on import) or derive it at read time like the scheduler does.

**D2 — Low — Anki day-count conversion is DST-asymmetric between import and export.** `toAnki` converts a review `due` via DST-aware calendar `studyDayIndex` difference (`AnkiScheduling.kt:247-260`); `fromAnki` instead computes `startOfDay(crt + due*86400000)` using the **fixed-offset** shim with the offset at the resulting instant (`AnkiScheduling.kt:120-124,295-298`). Across a 04:00-adjacent DST jump the two directions can differ by one study day, so an export→import round-trip can shift a due date. Anki's own `due_time` is `now + (due - days_elapsed)*86400`, i.e. fixed-length days ([rslib `browser_table.rs`](https://github.com/ankitects/anki/blob/main/rslib/src/browser_table.rs)), so the *intended* semantics need confirmation before either side is "correct". *Why missed:* the DayBoundary rewrite added a DST case only for exposure counts (`ExposureInStudyDayTest:130`); `DayBoundaryTest` still uses fixed-offset zones (`:31-34,88-94`) and `AnkiSchedulingTest` has no DST fixture. *Fix:* pick one model and make both directions use `DayBoundary.boundaryOf(studyDayIndex(crt)+due)` with the DST-aware zone; add a round-trip test.

**D3 — Low/Nit — clip of non-finite weights diverges from upstream `clamp_safe`.** `Fsrs.kt:692-704` maps NaN to the shipped default and lets `coerceIn` send +Inf to the upper bound; upstream `clamp_safe` maps every non-finite value to the **lower** bound ([parameter_clipper_v7.rs](https://raw.githubusercontent.com/open-spaced-repetition/fsrs-rs/v7.0.0/src/parameter_clipper_v7.rs)). Only reachable from a hand-edited backup — the normal preference path rejects non-finite lists and falls back to defaults (`SpacedRepetition.kt:285-290`). Old C4, never fixed. *Fix:* map non-finite to `LOWER_BOUNDS[i]` to match upstream, or document the divergence.

**D4 — Low (latent) — attempt-delete recompute re-points `question_stats` back to the source paper.** `recomputeFromSurvivingResults` writes `paperId = a.paperId, paperTitle = a.title` from the surviving result's attempt (`Daos.kt:3121`); a cross-paper move deliberately re-points those display fields to the destination (`QuestionStore.kt:558-560`, `Daos.kt:3007-3010`). Deleting any attempt after a move restores the old paper label. `deleteAttempt` has no production UI caller (`McqRepository.kt:2374`, tests only), so this is dormant. Old D2, unchanged. *Fix:* if attempt deletion is exposed, derive display paper from the current category/card rather than the attempt.

**D5 — Low (corruption-only) — cross-paper re-key is source-agnostic.** `card_state` re-key is `INSERT OR IGNORE … SELECT … WHERE paperId != target` with no source filter/order (`Daos.kt:1729-1737`), and `review_log` re-key updates all non-target papers (`Daos.kt:2204-2207`). If one question has rows in two non-target papers (corrupt restore / partial earlier move), arbitrary memory is paired and histories merge. Under honest single-row moves the collision rules are correct (`QuestionStore.kt:528-560`). Old D1, unchanged. Owner decision to reject corrupt state or derive the current source paper is still open.

**D6 — Nit (crafted-file only) — unknown `cardState` string keeps stability but reads as NEW.** Importers default an unparseable enum to `NEW` while retaining `stability > 0` (`LegacyParser.kt:60-66`, `StreamingImportSession.kt:667-669`); `FsrsScheduler.next` then gates on `state != NEW && stability > 0` (`Fsrs.kt:134`) and calls `initialMemory`, discarding the memory. Old C1, not fixed (Room's enum read itself throws on an unknown DB string, so this needs a hand-edited JSON/`.apkg`). *Fix:* gate `hadMemory` on `stability > 0` alone.

## Re-verified as fixed since 0.0.53

- **C2 DST drift:** `DayBoundary` rewritten to calendar dates; `studyDayIndexOf`/`boundaryOfDate` resolve the target date's offset (`DayBoundary.kt:76-141`). All scheduling/stats paths inject a `TimeZone` (`Fsrs.kt:186,214`, `StudyStore.kt:63`, `StatsAggregator.kt:399` etc.); due instants land on 04:00 local across DST.
- **Anki day counts:** `toAnki` now uses `studyDayIndex` difference, integral by construction (`AnkiScheduling.kt:247-260`).
- **D3 stale-hash cache:** `settingsRestore` calls `studyStore.clearStaleChecked()` in `finally` (`McqRepository.kt:629`); query-time hash predicate still guards reads.
- **Localized errors & transaction hygiene:** whole-file exports/imports and failed paths use localized errors and one transaction (see round-2 library report).

## Checked, no defect

- FSRS-7 formula parity vs upstream v7.0.0: 34 default weights exactly match `inference_v7.rs`; clip bounds match `parameter_clipper_v7.rs`; forgetting curve / mixture / epsilon, both trace updates, fast-lapse 0.8 cap, difficulty reversion, 7-step Newton + bisection solver and SM-2 bridge (`Sf=0.8S`, `D=5`, `HISTORICAL_RETENTION=0.9`) all match `model_v7.rs` ([URLs above](https://raw.githubusercontent.com/open-spaced-repetition/fsrs-rs/v7.0.0/src/model_v7.rs)). Only the D3 clip edge differs. Deliberate deviations (8-decimal rounding, whole-day intervals, exposure cap, zero-sentinel) are documented and internally consistent.
- Migration chain 3→28 all registered (`AppDatabase.kt:1254-1261`); dual-shape v27 guards use `sqlite_master` + `PRAGMA table_info` with per-column checks (`:995-1020`), so both v27 parents upgrade and Room post-validation confirms the final schema.
- `Fsrs7Upgrade` CAS on the replay's last `lastReviewedAt` (`Daos.kt:1649-1662`), resumable cursor, seeded-history rule, fast-trace repair/fill — consistent with `FsrsReplay` and the live scheduler.
- `recordStudyReview` writes card state and review log in one transaction from a config snapshot (`StudyStore.kt:593-641`); `applyQuestionStats` runs inside the attempt's transaction (`HistoryStore.kt:311,371`).
- `bumpQuestionStats` "latest graded+answered" standing matches `recomputeFromSurvivingResults` (`Daos.kt:2955-2962,3157-3179`); counters, rep flags and skip-only fallback agree.
- Backup preserves `stabilityFast`, `cardState` and all log traces (`Backup.kt:549-579`); v2 composite NUL key + bare alias resolve through the single `lookupCardSchedule` helper.
- No SQL injection: dynamic SQL interpolates only compile-time constants (`AppDatabase.kt:1074,1130,1211`); all user ids are bound parameters.
- `isStorableFit` keeps `steps > 0` and finite, strictly-better loss (`FsrsTuner.kt:169-174`); `trainingScheduler` uses unclipped weights — both as documented.
- Signals (`QuestionSignals.kt`) and `FsrsReplay` exposure counting use the same pools/day boundary as the live path.

## Owner decisions

1. **D1:** should Anki imports honour the current configured leech threshold, or keep Anki's historical 8 at import and recalc on first study?
2. **D2:** Anki models a day as 86400 s; the app uses calendar study days. Confirm which the round-trip should preserve, then unify `fromAnki`/`toAnki` and add a DST test.
3. **D3:** align clip-to-lower-bound with upstream, or keep the default-value handling as a deliberate documented divergence?
4. **D4/D5/D6:** keep attempt deletion test-only (latent) or harden it; reject multi-paper duplicate card rows on restore; reject unknown `cardState` in imports vs. fall back to `stability > 0`.
