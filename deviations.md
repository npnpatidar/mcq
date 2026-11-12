# Deviations from `plan.md` — audit of `main` since `0.0.50`

Audited `0.0.50..80f42df` (9 merged PRs: #70–#78; 60 files, ~10,909 insertions).

Method: source review against `plan.md`. The app and unit-test sources compile
(`:app:assembleDebug`, `:app:compileDebugUnitTestKotlin`); the test suites were **not
executed** for this audit, and Robolectric/SQLite tests cannot run on the aarch64 host.
CI remains authoritative for those paths.

**Fix status (branch `feature/ladder-epoch-fixes`):** findings 1, 2, 3, 6, 7 and 8 are
fixed. Findings 4 (whole-log materialisation) and 5 (Brier per grade, follow-up split)
remain open — they are plan deviations rather than wrong results.

Verdict: Phases 1, 3, 4 and the table-row-height fix are faithful to the plan. Phase 2/5
is correct at the unit level but the epoch boundary is not enforced, which is the one
change that should be fixed before the feature is considered done.

---

## 1. High — the calibration report pools the whole log, not the current epoch

`app/src/main/java/com/mcqapp/domain/stats/Calibration.kt:242`,
`app/src/main/java/com/mcqapp/data/repository/StatsStore.kt:194-204`

`StatsStore.loadCalibration` accumulates every `review_log` row into
`ArrayList<Calibration.Row>` and passes the list to `Calibration.report`. Nothing filters on
`reviewedAt >= epochStart` or on `ladderVersion`, so `contrast1`, `readiness.pairs`, the
card counts and the decision all include pre-change evidence.

Plan §2.4/§5: contrasts and readiness are pooled only within the current epoch, and the
epoch resets when a cut changes.

Consequences:

- After a cut change, δ1 is not re-estimated on the new epoch; the phase-5 keep/revert rule
  measures a mixture of the old and new thresholds.
- The safety valve's "last two closed blocks" can include a pre-change block.
- The 300-pair volume floor and the 40-card precision gate never reset, so readiness is
  inherited from the previous epoch.

`CalibrationTest` covers pairing, exclusions, block structure and follow-up, but no test
asserts that pre-epoch pairs are excluded.

## 2. High — a refit freezes the ladder until some cut change moves the epoch

`app/src/main/java/com/mcqapp/data/repository/StatsStore.kt:215`,
`app/src/main/java/com/mcqapp/domain/stats/Calibration.kt:197`

`refitInEpoch` is true whenever *any* row in the epoch has a `fsrsFitVersion` different from
the current config. A refit bumps `fsrsFitVersion` but does not move `epochStart`, so every
pre-refit row keeps the flag true indefinitely and `Readiness.ready` stays false.

Plan §1.6/§7: "A refit ends the epoch." The resulting epoch reset is not implemented, so
the ladder stays frozen after the first fit until a cut change (manual edit, accepted
proposal, rollback or restore) advances `epochStart`.

## 3. Medium — closed evidence blocks are not persisted

`app/src/main/java/com/mcqapp/data/repository/LadderStore.kt:153` (`recordBlock`, no
production caller), writes only at `:168` (`applyProposal`) and `:197` (`dismissProposal`).

`ladder_blocks` rows are inserted only when a block produces a proposal that is then applied
or dismissed. Blocks closed as `no-change` (no proposal), `insufficient-evidence`,
`frozen-refit`, etc. are never written. `LadderStore.recordBlock` exists but has no
production or test caller.

Plan §1.2/§2.4 require one row per closed block, *including blocks that decided nothing*,
and call out the stalled-controller ambiguity this leaves: a controller that stops
proposing looks the same as one that is working, because the silence is not recorded.

## 4. Medium — the whole review log is materialised

`app/src/main/java/com/mcqapp/data/repository/StatsStore.kt:194-204`

Plan §2.5 specifies keyset paging by card (page size 500), aggregating per card and keeping
only the epoch's pairs. The implementation builds a `List<Calibration.Row>` for the entire
log before computing, so memory grows with total review history — the blow-up the plan's
risk table names.

## 5. Low — diagnostics are a subset of the plan

`app/src/main/java/com/mcqapp/domain/stats/Calibration.kt`, `RetentionSection.kt`

- No per-grade Brier line (plan §2.5 asks for Brier per grade).
- Follow-up diagnostics are only due-vs-observed; the plan asks for the split into
  not-yet-due / overdue / reset / edited.

## 6. Low — calibration cache is unsynchronised

`app/src/main/java/com/mcqapp/data/repository/StatsStore.kt` — `calibrationCache` is a plain
`HashMap` reachable from the Stats view model (`Dispatchers.Default`) and from the
session-end controller (`Dispatchers.IO`). Concurrent access is unguarded.

## 7. Low — `stats_ladder_hard_note` wording

The string reports "%d pairs is not enough" but is shown whenever the Hard *card* count is
below the 40-card gate, which can read oddly when the pair count is large. The gate itself
is correct.

## 8. Cosmetic — `plan.md` status is stale

The status line still says "uncommitted working note … nothing built, released or pushed"
although the plan is committed and phases 1–5 are merged.

---

## Verified as implemented (no deviation)

- **Table row height fix** (`ContentElements.kt`): each row uses
  `Modifier.height(IntrinsicSize.Min)` and every cell `.fillMaxHeight()` before its border,
  so the border lands on the row boundary. `TableLayoutStructureTest` pins both. Only
  cosmetic side effect: `IntrinsicSize.Min` may reserve slightly more row height than a
  wrapped cell needs.
- **Phase 1 schema v26**: all seven `review_log` columns, `ladder_versions`,
  `ladder_blocks`, `study_sessions`, the `(paperId, questionId, reviewedAt)` index,
  registration in both migration arrays, exported `26.json` matching the DDL, and a
  migration test that replays the production chain from v25 and asserts old rows keep
  `ladderVersion = 0` / null provenance.
- **Single cut writer**: Settings edits, reset, accepted proposals, dismissals, rollback and
  restore seed all route through `LadderStore`; sticky `EDITED`, and `predictedRecall` null
  rules for new and imported cards, are implemented and tested.
- **Session lifecycle**: `endSitting()` is called from `finishEmpty`, `finishSession` and
  `onCleared`, guarded by an atomic flag, runs on an IO scope, and skips zero-review
  sittings.
- **Grade provenance threading**: `proposedGrade` / `gradeEdited` /
  `readingAllowanceSeconds` carried through `next()`, `stop()` and classic `grade()`; the
  config snapshot is passed into `StudyStore.recordStudyReview`; the `enableNewCards`
  mid-session-fit bug is fixed.
- **Backup v2**: `VERSION = 2`, all three tables wired through the full writer, the
  streaming writer, both readers and both restore paths; DTO defaults keep v1 backups
  decoding; streaming round-trip covered.
- **Phase 2 statistics**: adjacent contrasts with the correct sign/direction, all seven
  exclusion rules, due-ness follow-up gate, precision tiers (BCa ≥ 40, t for 20–39, none
  below 20), degenerate-case handling, fixed 28-day blocks with a 90-day horizon, and the
  `FsrsReplay` `(paperId, questionId)` pairing fix.
- **Phase 3 rounding**: milliseconds are the source of truth, round-half-up once at Check,
  display truncates, `changeGrade`/`stop` reuse the frozen value, and `dwellBand` is
  NEUTRAL at 0 s and agrees with `inferGrade` from 1 s (property test included).
- **Phase 4/5 ladder**: one-step size, bounds, 5 pp + MDE guards, one step per block,
  sanitized proposal output, refit freeze, safety-valve sign logic, the default-off toggle
  inside the existing Study settings section (no sixth section), and controller execution
  at sitting end only.

## Not verified by execution

Robolectric/SQLite paths — migrations, DAO round-trips, backup round-trip, ladder store —
are verified by reading only. Run them on CI (x86_64) before the release.
