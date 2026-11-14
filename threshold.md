# Absolute dwell thresholds (6 s / 60 s) — implementation plan

Branch: `feature/absolute-dwell-thresholds` (cut from `main` @ `7f47596`, tag `0.0.51`).
This file is the tracking plan for the change; tick items as they land, and delete it or
fold it into `README.md` before the release PR is merged.

## 1. Decisions

| # | Decision | Why |
|---|----------|-----|
| D1 | Grade on **absolute** dwell: `dwell < 6 s` → Easy, `dwell >= 60 s` → Hard, else Good (or Again for wrong/guess/skip). | The reading bonus made Easy the modal grade (63 % of 1 089 reviews; 54.4 % of the 114 versioned rows). No additive rule can fix that: even `fastSeconds = 0` leaves ~25 % Easy because the median answer arrives before the reading budget expires. |
| D2 | Delete the **reading allowance** entirely: `easyThresholdSeconds`, `dwellLadder`, `READ_CHARS_PER_SECOND`, `IMAGE_SECONDS`. | Nothing is added to a threshold, so the allowance has no job. It also removes the "allowance that was never subtracted" defect (`StudyViewModel` stored `8 + b` into a column documented as the subtracted amount). |
| D3 | Delete the **adaptive ladder**: `LadderPolicy`, `LadderController`, `LadderStore`, `ladder_blocks`, the settings switch, the Stats controls. | It never ran (0 usable calibration pairs, no epochs), needs ~118 days before its first closed block, and its floor (`3 + b`) still left ~39 % Easy. |
| D4 | Keep the **calibration report** (Statistics → Retention) as read-only evidence. | It answers "do Easy labels predict recall" from the log and needs no controller. Removing the display would remove the only feedback loop. |
| D5 | Keep `review_log.readingAllowanceSeconds` and `review_log.ladderVersion` (renamed `gradingVersion` in Kotlin only). New rows write `null` / a `grading_versions` id. | The log is append-only history. Old rows must not be rebuilt; the fields keep existing backups importable and old analyses reproducible. |
| D6 | Replace `ladder_versions` with `grading_versions`, copying ids. | Every pre-v27 row's `ladderVersion` must still resolve to the cut pair that graded it, so the log stays re-gradable offline. |
| D7 | Bump the rule version (`grading_rules_version = 1`): a cut pair stored by an older build is ignored on read, and the first versioned read writes a fresh `prior` row for 6/60 so the calibration epoch starts at the rule change. | A stored 8/30 was tuned against the reading bonus; silently keeping it would grade the new rule with the old pair, and reusing its version id would pool pre-v27 reviews into the new epoch. |
| D8 | Turn the report's direction into a **read-only** suggestion (`Suggested cuts: try 4 s / 60 s…`) instead of an applied change: one second per 5 pp outside the band, capped at 4 s, Easy cut only. | Removing the ladder removed the only thing that translated "too wide" into a number. The contrast tells direction and magnitude in pp, not an optimum, so the app names a bounded next step and the learner decides. Hard is never moved: δ2 is not acted on. |
| D10 | Offer the first pair from **answer times** (`p10` / `p90` of correct, unguessed, timed answers from clock-neutral versions), before any Easy/Hard label exists. | The contrast needs Easy-vs-Good pairs, so turning correctness-only off starts a months-long wait rather than producing a number. Dwell is recorded in both rules, and a neutral clock keeps it unbiased, so the first pair can come from the pace the learner already has. |
| D9 | Add a `Grade on correctness only` switch: correct → Good, wrong/guess/skip → Again, the clock never decides the grade or its colour. The switch hides both cut fields and the Stats calibration card, keeps recording the dwell, and starts a new grading version (`reason = rule`). | Some learners do not want time to grade them. Ignoring the clock is a rule change, not a cut value, so it needs its own epoch; the dwell is still the input any later re-tune would need, so it keeps being stored. |
| D11 | A **new install starts with correctness-only on**; an upgrade keeps the rule it had, and a restored install keeps the rule in its backup. | The rule is the measurement condition for the answer-time cuts: the dwell of a tinted clock is not free evidence, so a learner who starts on timing cannot produce that suggestion later without first spending weeks on correctness-only. Seeding it makes the unbiased sample the default path; scoping it to fresh installs keeps an upgrade from silently changing how it grades. |

Expected distribution on the recorded 812 scorable reviews (replay, Again fixed):

| Rule | Again | Hard | Good | Easy |
|------|-------|------|------|------|
| Old `8 + b` / `30 + b` (114 v1 rows) | 13.2 | 9.6 | 22.8 | **54.4** |
| **New absolute 6 / 60** | 11.0 | 5.4 | **75.5** | **8.1** |
| For comparison: absolute 8 / 60 | 11.0 | 5.4 | 65.8 | 17.9 |
| For comparison: absolute 8 / 90 | 11.0 | 1.8 | 69.3 | 17.9 |

## 2. Scope

In scope:
- grading rule (`Study.inferGrade`, `Study.autoGrade`), study clock, defaults;
- schema 26 → 27 and its migration;
- backup read/write/restore;
- settings store + UI (remove the adaptation switch, relabel the cuts);
- Stats retention surface (remove the ladder decision/rollback UI);
- tests and docs.

Out of scope:
- changing FSRS scheduling weights or retention target;
- new grading signals (selection revisions / doubt) — noted as a follow-up;
- re-importing or rewriting historical review rows.

## 3. Schema v27

- `review_log`: unchanged on disk. Kotlin field `gradingVersion` maps to the existing
  `ladderVersion` column (`@ColumnInfo(name = "ladderVersion")`). `readingAllowanceSeconds`
  stays, nullable, and is `null` on every new row.
- `grading_versions` (was `ladder_versions`): `id`, `easySeconds`, `slowSeconds`,
  `effectiveFrom`, `reason` (`prior` / `manual`). Index on `effectiveFrom`.
- `ladder_blocks`: **dropped**.
- `MIGRATION_26_27`: create `grading_versions`, copy `ladder_versions` rows by id, drop
  `ladder_blocks` then `ladder_versions`.
- `app/schemas/com.mcqapp.data.local.AppDatabase/27.json` exported.

## 4. Work breakdown

### 4.1 Domain + study

- [x] `SchedulerConfig.fastSeconds = 6`, `slowSeconds = 60`; `Study.FAST_SECONDS = 6`,
      `Study.SLOW_SECONDS = 60`.
- [x] `inferGrade` / `autoGrade`: absolute cuts from `config` only; removed the
      `fastSeconds` / `slowSeconds` override parameters.
- [x] Deleted `easyThresholdSeconds`, `DwellLadder`, `dwellLadder`, reading-bonus constants.
- [x] `StudyViewModel`: clock and grading read `config.fastSeconds` / `config.slowSeconds`
      directly; no allowance state, no `readingAllowanceSeconds` writes.
- [x] Removed the end-of-sitting `runLadderController()` call.
- [x] `SchedulerConfig.ladderVersion` → `gradingVersion` (DataStore key kept).

### 4.2 Persistence

- [x] `Entities.kt`: `GradingVersionEntity`; deleted `LadderVersionEntity`,
      `LadderBlockEntity`; `ReviewLogEntity.gradingVersion` mapped to the old column.
- [x] `Daos.kt`: `GradingRulesDao` (versions only; block queries deleted).
- [x] `AppDatabase.kt`: v27, entity list, `MIGRATION_26_27`, migration registration.
- [x] `27.json` exported by Room.
- [x] New `GradingRulesStore` (PRIOR / MANUAL only): seeds a version on first read,
      records a version only when a cut changes, repairs a missing row on read, seeds after
      restore.
- [x] `McqRepository`: uses `GradingRulesStore`; `ladderVersion()` → `gradingVersion()`;
      removed apply/dismiss/rollback/run-controller entry points and the
      `readingAllowanceSeconds` write parameter.
- [x] `SettingsStore`: removed `automatic_ladder_adaptation`; added
      `grading_rules_version`; ignores a pre-v27 cut pair and reports
      `gradingVersion = 0` until a versioned read seeds one.
- [x] Upgrade epoch: the first `currentVersion()` after a rule bump writes a new
      `prior` row (next id, 6/60) so calibration never pools old- and new-rule rows.
      A reset drops the version key for the same reason.
- [x] `StatsStore`: `versionStart` now comes from `grading_versions`; returns only the
      calibration report.
- [x] `grading_versions.correctnessOnly` (v27, unreleased so the table was extended in
      place): which rule graded the reviews under that version.
- [x] `GradingRulesStore`: a rule switch writes `reason = rule` and the flag; a cut edit
      under the switch writes `reason = manual` with the flag still set.

### 4.3 Delete the ladder

- [x] Deleted `LadderPolicy.kt`, `LadderController.kt`, `LadderStore.kt`.
- [x] Deleted their tests: `LadderPolicyTest`, `LadderControllerTest`, `LadderStoreTest`,
      `LadderBackupStreamingTest`.
- [x] `StatsModels.kt`: removed `ladderEvaluation`, `ladderCanRollback`,
      `ladderLastChange`, `LadderChange`.
- [x] `StatsViewModel`, `StatsScreen`, `RetentionSection`: removed the decision, rollback
      and last-change blocks; calibration report retained.
- [x] `Calibration.kt`: `LadderGrade` → `GradeClass`; `Row.ladderVersion` →
      `gradingVersion`. Report math unchanged.

### 4.4 Backup

- [x] `McqBackupFormat.GradingVersionRow`; `ladderVersions` wire key kept via
      `@SerialName`; `ladderBlocks` is read and skipped.
- [x] Source, streaming writer, streaming sink and restorer updated; old backups (format 2,
      dbVersion ≤ 26) still import.
- [ ] Round-trip check on a real `0.0.51` backup via CI (needs SQLite → not runnable on
      this aarch64 host).

### 4.5 Settings / UI copy

- [x] Removed `Adapt the Easy cut automatically` switch (`SettingsScreen`,
      `StudySchedulerSection`, `SettingsViewModel`, strings).
- [x] Relabelled the two cut fields: absolute, clock starts when the card appears.
- [x] Verified no remaining UI text or string resource promises "extra reading time"
      (`grep -rn "reading bonus\|reading allowance" app/src/main`).

### 4.6 Tests

- [x] `StudyDwellBandTest` rewritten for 6/60, first-second agreement across three configs,
      rounding, neutral second.
- [x] `SpacedRepetitionTest`: removed reading-bonus tests and override-parameter tests;
      default expectations moved to 6/60 (Easy < 6, Good 6–59, Hard ≥ 60).
- [x] `CalibrationTest`: `gradingVersion`.
- [x] `BackupRestoreRoundTripTest`: seeds `grading_versions`, dumps it.
- [x] `MigrationTest`: 25→27 test updated; new 26→27 test asserts the version row is copied
      and both ladder tables are gone.
- [x] `RepositorySettingsTest`: adaptation test deleted; new test proves a stored 8/30 pair
      from a v26 build is ignored; whole-config assertions use `gradingVersion`.
- [x] `GradingRulesStoreTest` (new, CI-only): seed once, manual cut edit, no-op save, reset,
      crash repair, restore numbering, and the upgrade epoch.
- [x] `StatsRetentionStreamTest`: ladder payload assertions removed.
- [x] `StudyViewModelPullTest`: grading version instead of ladder version + allowance.
- [x] Full pure-JVM suite green locally (163 classes).
- [x] CI green for the Robolectric/SQLite suites (run 43473648330, 2 108 tests, 7 skipped).

### 4.7 Docs

- [x] `README.md` §Simplified footer: thresholds are now absolute 6 s / 60 s, no reading
      bonus.
- [x] `README.md` "Two clocks": remove "reading time included" from the tint description.
- [x] `README.md` §3.6 / §3.7 (settings tables): defaults 6 s / 60 s, delete the reading
      bonus paragraph, update the summary table rows.
- [x] `README.md` stray "default 8 s / 30 s" references (`grep -n "8 s\|30 s\|reading bonus"`).
- [x] Doc comment on `ReviewLogEntity.readingAllowanceSeconds`: call it a legacy v26 column
      that new rows leave null.
- [x] Move/remove the orphaned v26 KDoc above `MIGRATION_26_27` in `AppDatabase.kt`.
- [x] Deleted the now-unused `stats_ladder_*` string resources.

### 4.7b Suggested cuts (read-only)

- [x] `Calibration.suggestCuts(report, fast, slow)`: `NEEDS_EVIDENCE` until the readiness
      and follow-up gates close, then `KEEP` / `STEP_DOWN` / `STEP_UP` / `AT_LIMIT`. Step =
      one second per 5 pp outside the band, capped at `MAX_STEP_SECONDS = 4`; Easy only.
- [x] `RetentionData.cutSuggestion` filled by `StatsStore.loadRetention` from the config in
      force, so the line always names the pair the report was measured against.
- [x] Retention section renders the line under the two contrasts; it never applies anything
      and says so when it names a different pair.
- [x] `CalibrationTest`: keep, one-step down, cap, inside-band no-move, step up, both range
      ends, and the three evidence gates.
- [x] `README.md` Tab 2 calibration row describes the line and its policy.

### 4.7c Correctness-only grading

- [x] `SchedulerConfig.correctnessOnlyGrading`; `inferGrade` returns Good before reading the
      cuts, so Easy/Hard are never inferred.
- [x] `RepositorySettings`: `correctness_only` key, read/write/reset; `writeCuts` carries the
      mode in the same edit as the pair.
- [x] `GradingRulesStore`: a rule switch writes `reason = rule`; a cut edit under the switch
      stays `manual` and keeps the flag. `GradingVersionEntity.correctnessOnly` records it.
- [x] Backup: `GradingVersionRow.correctnessOnly` (default false, so old files import).
- [x] Settings: switch (`setting-grade-correctness-only`); both cut rows and their footnote
      disappear while it is on, and the stored pair survives as the fallback.
- [x] Study: the clock keeps counting and the dwell is still stored, but the tint stays
      neutral (`StudyUiState.correctnessOnlyGrading`).
- [x] Stats: `RetentionData.timeBasedGrading` hides the calibration card (contrasts,
      readiness, suggested cuts) — with no Easy/Hard labels it has nothing to measure.
- [x] Tests: every dwell → Good, wrong/guess/skip → Again; the pair is untouched when the
      switch is flipped back; store round-trip and reset; RULE version on switch, MANUAL with
      the flag on a cut edit; the v26→v27 copy defaults the column to 0.
- [x] `README.md`: footer, clock tint, settings table, switch section, Tab 2.

### 4.7d Answer-time suggestion (stage 1)

- [x] `Calibration.Row.elapsedSeconds` and `correctnessOnly` (the rule that produced the
      row, resolved through `grading_versions` in `StatsStore`).
- [x] `Calibration.suggestCutsFromDwell`: p10/p90 over correct, unguessed, timed answers from
      clock-neutral versions; gates 200 rows / 30 cards / 14 days; clamps 2 s, 60 s gap
      floor 5 s, 120 s ceiling; integer percentile arithmetic (0.9 × 199 is not 179.1).
- [x] `RetentionData.dwellSuggestion`; `StatsStore` returns it only under correctness-only, so
      it never competes with the contrast suggestion.
- [x] Retention section: `Suggested cuts` card replaces the calibration card under that rule,
      says it measures time rather than retention, and names the pair to enter when timing is
      switched on.
- [x] Tests: percentile pair, exclusion of wrong/guess/skip/zero/null/tinted rows, all three
      gates, both clamps, and the "agrees with the stored pair" case.
- [x] `StatsRetentionStreamTest`: the streamed build's report and both suggestions are nulled
      before comparing against the in-memory oracle.
- [x] `README.md`: Tab 2 and the switch section describe the two-stage tuning.

### 4.7e Fresh-install default

- [x] `SettingsStore(context, freshInstall)`: the scheduler-config flow seeds
      `correctness_only = true` before its first emission, and only when the install is fresh and
      no preference is stored — an upgrade and a restored install are left alone.
- [x] `McqApplication.onCreate` captures `freshInstall` from the absence of the database file
      before Room opens it; `McqRepository` passes it on; `AppDatabase.NAME` names the file.
- [x] The first version row of a fresh install carries `correctnessOnly = 1`, so the answer-time
      evidence is never read from tinted dwells.
- [x] Tests: a fresh install starts on correctness-only and stores it; an upgrade keeps the timed
      rule; a restored install and an explicit choice are never overwritten; the fresh version row
      records the rule.
- [x] `README.md` and the switch help state the default and the order (collect first, then turn
      timing on with the suggested pair).

### 4.8 Housekeeping

- [x] `grep -rn "ladder" app/src/main app/src/test README.md`: only the historical
      migration SQL, the retained `ladderVersion` wire/column names and old-backup skip
      remain.
- [x] `threshold_analysis.md` ships with the branch: it is the replay that chose 6/60, and
      the numbers in §1 must stay reproducible.
- [x] This file ships too, as the design note for the PR; fold it into `README.md` or
      delete it before the release PR merges.
- [ ] `freebuff-chat-*.md` is a local transcript, not part of the change — keep it out of
      the commit.
- [ ] Conventional commit message for the branch; PR as draft until the owner says go.
      Commit/push is the owner's call.

## 5. Verification

```bash
# NixOS arm64 host (repo is /home/naresh/repo/mcq)
. apk-build/env.sh
sh gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin \
  $MCQ_AAPT2_OVERRIDE --dependency-verification=lenient --no-daemon
```

- Pure-JVM tests only locally: pick test files without `^import org.robolectric` that
  contain `@Test`, pass **unquoted** class names.
- `MigrationTest`, `BackupRestoreRoundTripTest`, `StudyViewModelPullTest`,
  `RepositorySettingsTest`, `StatsRetentionStreamTest` use Robolectric/SQLite and fail on
  aarch64 (`Robolectric native runtime is not supported on Linux (aarch64)`); CI is
  authoritative.
- CI run 43473648330 on `feature/absolute-dwell-thresholds` is green: 2 108 tests, 7
  skipped, including the Robolectric/SQLite suites. Both APK artifacts are installed at
  `/home/naresh/Download/app-debug.apk` and `app-release.apk`.
- After installing: confirm a 6 s / 60 s session grades as expected.

Post-ship checks from the real log:
- every `gradeSource = AUTO` row has `dwell < 6` ⇒ `rating = 4`, `dwell >= 60` ⇒ `rating = 2`;
- `readingAllowanceSeconds` is null on new rows and unchanged on old ones;
- a Settings save of a cut writes exactly one `grading_versions` row with `reason = manual`.

## 6. Risks and rollback

- **Migration**: `MIGRATION_26_27` copies rows by id then drops two tables. Covered by the
  new `MigrationTest`; Room's identity check proves the final schema. If a device fails to
  open, the destructive fallback is not configured — the migration must be correct.
- **Historical comparability**: pre-v27 rows were graded under `8 + b` / `30 + b`; the
  calibration epoch starts at the first `grading_versions` row so the two rules are never
  pooled (the report will show a mostly empty epoch until new reviews accumulate).
- **Cut pair on upgrade**: deliberately reset once to 6/60. A learner who had customised
  8/30 must re-save it; this is visible in the field values.
- Rollback is `git revert` of the branch before release; after release, a follow-up
  migration would be needed (the ladder tables are gone).

## 7. Follow-ups (not this change)

- Take the 6/60 labels as fixed until the calibration report has ≥ 300 usable pairs
  (~months at this volume), then re-tune from the report rather than a fresh analysis.
- If Easy (8.1 %) or Hard (5.4 %) prove too rare in practice, revisit 8/60 or 6/90 — the
  replay is a one-line change in the analysis script.
- Consider a doubt signal (selection revisions) for Hard, which a clock cannot express.
