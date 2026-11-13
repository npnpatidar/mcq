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
- [ ] CI green for the Robolectric/SQLite suites (authoritative on x86_64).

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
