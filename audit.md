# Audit — `feature/absolute-dwell-thresholds`

- **Range reviewed:** `main...HEAD` — 20 commits, 88 files, +6 788 / −7 925
- **Head:** `a62a94c legacy docs deleted` (docs-only)
- **Code tip:** `5e91f1e fix(grading): treat an answer inside the first second as untimed`
- **Base:** `main`; PR **#81** is open as a **draft**, cut from `main`
- **CI:** code tip green in run **43623020031** (build, unit tests, lint, emulator UI). The branch tip `a62a94c` changed only markdown and has no run of its own.
- **Method:** three specialist read-only passes — (D) design & domain model, (I) implementation & integration, (T) tests, copy, docs & release — followed by a supervisor verification pass on the highest-severity claims. Reviewer T's pass was completed by the supervisor because the subagent quota was exhausted; every T finding below was re-derived directly from the tree.
- **Limits:** this host is ARM64/Termux. Robolectric/SQLite tests, the Compose runtime and the emulator cannot run locally, so every database, migration, backup and UI claim here is static code-reading unless stated otherwise. CI is the only executed evidence. The latest debug/release APKs are in `/sdcard/Download`.

**Verdict: do not un-draft yet — but the branch is close.** The feature's core is sound and every original blocker is genuinely closed. What remains is one **inherited** Major (a full restore restarts the calibration epoch and excludes the restored history from the evidence report), one new inconsistency in the retired-pair policy, a cluster of copy that the last three commits made false, and one new test assertion that can never execute. There is no Blocker, no schema or migration break, and no data-loss path in normal use.

---

## Severity summary

| Severity | Count | Themes |
|---|---|---|
| Blocker | 0 | — |
| Major | 1 | Restore restarts the calibration epoch (inherited from `main`) |
| Minor | 10 | Retired-pair reset gap, dead test branch, copy/PR drift, migration resolvability, schema hash, dead doc references, `fast = 1`, sub-second gate exclusion, copy precision, gate wording |
| Nit | 6 | Tint freshness claim, test seams in production, static probe field, `AT_LIMIT` wording, "agree with" wording, 5 pp step wording |

All original Blockers **B1–B4** and Majors **M1, M2, M4–M7, M9, M10** are fixed or closed as recorded below. **M8** is accepted by owner decision and **M3** is an owner decision.

**State after the four implementation batches (2026-11-15).** No Blocker and no Major is
open: **F1** is fixed and hardened against a mismatched pair and an unusable timestamp
(**R1**), **F2** is fixed, **F3/F5** fixtures execute, **F4** copy matches the app, **F6** is
settled by a from-scratch regeneration, **F7** documents are deleted, and the two later
rulings **F8** (2 s Easy floor) and **F9** (sub-second rows count as one second in the
suggestion only) are implemented with tests. What remains is deliberate: the 80 `plan §N`
code citations, the accepted **M8** no-27→28 decision, the **minor 6** migrated-row contract,
the **F11** nits, three untested UI behaviours, the untested legacy *streaming* import, and the
deleted-analysis provenance of the PR's 812-row figure. CI green at `18f55e2`; the branch
needs one more green run on the batch-4 commit before release.

---

## Findings

### F1 — A full restore restarts the calibration epoch, so every restored label is excluded *(Major, inherited)*

**Evidence.** `McqRepository.settingsRestore` (`McqRepository.kt:479-487`) runs `settings.restore(snapshot)` and then `gradingRules.seedAfterRestore()`. That method (`GradingRulesStore.kt:127-155`) computes `maxVersionId + 1`, rewrites `ladder_version` to the new id, and inserts a `prior` row with `effectiveFrom = System.currentTimeMillis()` (`:174`). `StatsStore.configWithEpoch` (`StatsStore.kt:322-336`) resolves the current config's id to that new row, so `versionStart` is "now". `Calibration.report` then drops everything earlier in both passes:

```kotlin
if (epochStart > 0L && row.reviewedAt < epochStart) return@forEach   // Calibration.kt:495
if (epochStart > 0L && label.reviewedAt < epochStart) continue       // Calibration.kt:514
```

The backup's `grading_versions` rows are re-inserted with their original ids and `effectiveFrom` (`Backup.kt:1301-1306`), and the review rows are intact, so the reseed is not needed for resolvability: every restored review already names a present row that graded it.

**Why it matters.** The feature's contract is that an epoch marks "reviews graded under these cuts". Restored reviews *were* graded under exactly those cuts, and the reseed even writes the same pair under a new id. After any full restore on a timed install, the evidence report — pairs, per-grade table, both contrasts, follow-up coverage and readiness — comes back empty and `epochDays` restarts at 0, so the learner sees "not enough evidence" for history they just restored. The forecast/Brier chart (built by the aggregator, `StatsAggregator.kt:906-919`) and the answer-time suggestion (no epoch filter, `StatsStore.kt:291-296`) are unaffected; the loss is the cut-evidence report.

**Not a regression.** I checked `main`: `LadderStore.seedAfterRestore` stamped a new row at `now` in the same way, and `StatsStore`/`Calibration` already filtered by `versionStart` (`git show main:.../LadderStore.kt`, `git show main:.../StatsStore.kt:184-222`). The branch did not introduce the behaviour, but v27 makes "the cuts in force are evidence" the headline promise, which raises the cost.

**No test asserts the intent.** `GradingRulesStoreTest.restoreSeedsANewPriorVersionContinuingTheNumbering` (`:448-466`) pins only the id and the reason string. Nothing asserts what the report should contain after a restore.

**Suggested fix.** When the restored current version id already names a row whose pair equals the restored pair, keep that id and its `effectiveFrom` and skip the reseed; mint only when the pair differs or the row is missing (`ensureVersionLocked` already repairs the missing case at `GradingRulesStore.kt:216-230`). Add a test: after a restore, a review dated inside the backup's epoch is counted by `Calibration.report`. Alternatively, keep the behaviour and document the epoch restart in README §3.9/restore — but that is a weaker contract.

**Owner ruling (2026-11-15): option A — fix it.** Reuse the restored version row and its `effectiveFrom` when the restored pair matches; mint only when it differs or the row is missing. The test above is required.

---

### F2 — A Reset as the first v27 action loses the retired pre-v27 pair *(Minor, new)*

**Evidence.** `McqRepository.resetSchedulerConfig` (`:291-294`) calls `recordResetCuts` only when `settings.resetSchedulerConfig()` reports that the effective pair changed. For a retired pre-v27 pair the effective pair is already 6/60, so it reports `false` and no row is written. `RepositorySettings.resetSchedulerConfig` (`:530-576`) sets `grading_rules_version` and removes `ladder_version`, overwriting the stored pair in the same edit, but never calls `retainRetiredCuts()` (`:487-495`).

**Why it matters.** The owner decided the retired pair is "kept silently"; the upgrade path honours that (`GradingRulesStore.kt:191-200` calls `retainRetiredCuts`). A learner who upgrades and immediately taps Reset is the one path where the pair is destroyed without a trace. `GradingRulesStoreTest.aFirstResetOfARetiredPairMintsNoManualRow` pins the no-row/epoch behaviour but not retention.

**Suggested fix.** Call `retainRetiredCuts()` inside the reset when `!cutsCurrent`, before the edit that overwrites the pair; or document the exception explicitly.

**Owner ruling (2026-11-15): option A — fix it.** Add the `retainRetiredCuts()` call on the reset-first path and pin it with a test.

---

### F3 — The new correctness-only stats assertion is unreachable *(Minor, new test gap)*

**Evidence.** `StatsRetentionStreamTest.streamedPayloadMatchesTheInMemoryBuild` now asserts the payload under both rules (`:227-249`), including `dwellSuggestion != null && cutSuggestion == null` under correctness-only (`:239-249`). But `setUp` builds `McqRepository(db, context)` with the production freshness probe (`McqRepository.kt:105-109`), and `seedLog()` inserts papers before the first config read, so `InstallFreshness` answers "upgrade", `correctness_only` is never seeded, and `timeBasedGrading` is always `true`. The whole `else` block is dead.

**Why it matters.** The prior M7 asked for a positive assertion on the new Statistics fields. The assertion exists but never runs, so a regression that drops `dwellSuggestion` under correctness-only would still pass. This is exactly the class of oracle gap the earlier audit flagged.

**Suggested fix.** Add a dedicated test (or a second case) that writes `correctness_only = true` through `updateSchedulerConfig` before loading, then asserts the correctness-only payload and that the timed contrast is null. Keep the existing timed loop as it is.

**Owner ruling (2026-11-15): add the fixture.** The correctness-only branch must execute in CI.

---

### F4 — Shipped copy and KDoc contradict the behaviour after `564497d`/`5641fd2`/`5e91f1e` *(Minor, new)*

| Location | Claim | Reality |
|---|---|---|
| `README.md:896` | correctness-only "Hides both cut fields below and the Stats calibration card" | the card is always emitted (`RetentionSection.kt:57-63`); only the evidence block is gated (`:254`) |
| `README.md:1004` | "The whole card is hidden while `Grade on correctness only` is on … That rule shows a different card instead" | chart + Brier stay; the dwell card is *added*, not a replacement |
| `README.md:2074` | "Statistics → Retention replaces the calibration card" | same |
| `README.md:2081` | retired pair "is not kept anywhere" | kept in `retired_easy_seconds`/`retired_slow_seconds` (`RepositorySettings.kt:487-494`), unread |
| `strings.xml:544` | "The Easy/Hard cuts and the calibration report are hidden while this is on" | report is shown |
| `strings.xml:546` | "Auto-graded Easy when the answer comes faster than this" | under 1 s is untimed/`Good` (`SpacedRepetition.kt:420-423,346`); 5.5–5.99 s rounds to 6 s and is `Good` |
| `StatsModels.kt:641-653` | card "is hidden instead of staying empty"; `dwellSuggestion` "Null under timed grading" | chart shown; suggestion computed under both rules and shown when it `changes` (`StatsStore.kt:208-212`) |

**PR #81 body** also carries four false or dead statements:

- "The cut rows and the whole calibration card are hidden while it is on" — false since `564497d`.
- "An upgrade keeps the learner's current rule **and cuts**" — the rule is kept; the cuts are retired to 6/60 (`GradingRulesStore.kt:136-155`).
- "Locally: **326** pure-JVM classes" — the tree has **165** files with `@Test` and no `^import org.robolectric` (262 test files total).
- "Evidence and method: `threshold_analysis.md`; plan and checklists: `threshold.md`" — both deleted at `a62a94c`.

**Step wording (prior minor 7, still open).** `README.md:1004` and `Calibration.kt:257` say "one second per 5 pp **outside** the band"; the code is `round(|δ1| / 0.05)` over the total gap (`Calibration.kt:260-264`; 0.06 → 1 s, 0.10 → 2 s, 0.40 → capped 4 s), which the tests pin.

**Suggested fix.** Correct the five README sentences, the two strings, the model KDoc and the PR body. No test references these strings, so nothing guards the drift.

**Owner ruling (2026-11-15): match the words to the app.** Correct the README, `strings.xml`, the model KDoc and the PR body. Do not re-hide the chart.

---

### F5 — The 26→27 migration test never proves a non-zero `ladderVersion` resolves *(Minor, new test gap)*

**Evidence.** `MigrationTest.createV26WithLadderHistory` copies `ladder_versions` id 7 with 8/30 (`:1357-1371`), but the review inserted by `createV25WithSeed` carries `ladderVersion = 0`, and the test asserts `0` (`:1401`). The comment above it ("every pre-v27 review row still resolves the cuts that graded it") is therefore untested for the case that matters: a v26 review stamped with id 7.

**Suggested fix.** Insert one v26 review with `ladderVersion = 7` and assert it reads back as 7 and that `gradingRulesDao().version(7)` exists after migration.

**Owner ruling (2026-11-15): add the assertion.** The migration test must prove a non-zero version resolves.

---

### F6 — `27.json` carries a hand-edited `identityHash` and CI has no drift check *(Minor, accepted by owner)*

**Evidence.** `app/schemas/com.mcqapp.data.local.AppDatabase/27.json:5` still carries `"identityHash": "c8196a592389f537cf16f3dca4c1c7e3"`, written by hand in `687dc2a` (the last commit to touch the file). The DDL itself matches `Entities.kt:353-368` and `MIGRATION_26_27` (`AppDatabase.kt:863-887`) column by column, and only `ladder_blocks`/`ladder_versions` were removed. `.github/workflows/` has only `build.yml` and `release.yml`, neither checking for schema drift, and Room rewrites the JSON at build time without committing it — so "CI is green" does not prove the hash.

**Why it matters.** The hash is what Room compares when opening an existing v27 database. A wrong hash would make a CI-installed v27 file fail to open. The owner decided no 27→28 migration is owed because no tagged release carried v27; that does not remove the need for the file to be self-consistent for anyone who installed a CI artifact.

**Suggested fix.** Run one build that regenerates the schema and confirm `git status` is clean (or add a drift check to CI). If it differs, commit the generated file.

**Owner ruling (2026-11-15): regenerate it.** Run the build, verify the generated `27.json`, and commit only if it differs. Keep the no-27→28 decision.

---

### F7 — Documentation deletion leaves dangling references and a live protocol for a deleted feature *(Minor, new)*

**Evidence.** `a62a94c` deleted `audit.md`, `threshold.md` and `threshold_analysis.md`; `7f47596` deleted `plan.md`. Consequences:

- **80** code comments still cite `plan §N` (e.g. `StatsAggregator.kt:65,77,156,714,1468,1752,2380`; `StatsModels.kt:107,200,336,653,665,780,799,804,823,845`; `Calibration.kt:387,470,518,669`), so the calibration's estimand, exclusions and interval rationale are no longer verifiable in-tree.
- `ladder-evaluation.md` still ships a pre-registered ABBA protocol for the ladder this branch deletes, including 8 s/30 s arms and "the controller from phase 5", and cites `plan.md:14`.
- The 6/60 tuning rationale survives only in commit `bae14c5`'s message and the (deleted) analysis docs.
- PR #81 links two deleted files as its evidence.

**Why it matters.** A released repository with 80 references to a missing design document is not reviewable. Deleting `audit.md` — the current evidence record — also removed the branch's own audit trail.

**Suggested fix.** Fold the surviving rationale into `Calibration.kt`'s KDoc or README; delete or rewrite `ladder-evaluation.md`; re-point the `plan §N` citations to stable text or drop them; keep the current audit record (this file) until the release is cut.

**Owner ruling (2026-11-15): delete the obsolete documents.** `ladder-evaluation.md` is deleted. `audit.md` stays as the branch's evidence record until the release is cut. The 80 `plan §N` citations live in code comments, not documents; they are recorded as a residual cleanup item rather than changed in this pass.

---

### F8 — `fastSeconds = 1` is legal and makes the Easy band permanently empty *(Minor, new)*

**Evidence.** `SchedulerConfig.sanitized` now keeps one second between the cuts (`SpacedRepetition.kt:226-229`) but permits `fast = 1`; the Settings range starts at 1 (`StudySchedulerSection.kt:377-383`). `inferGrade` requires `dwellSeconds in 1 until fastSeconds` (`:346`), so with `fast = 1` the Easy class is empty forever: `contrast1` is null, `suggestCuts` can only answer `NEEDS_EVIDENCE` (`Calibration.kt:252-255`), and the report can never say anything about the cut.

**Suggested fix.** Require `fast >= 2` in `sanitized` and the Settings range, or surface the empty Easy class in the report as a named degeneracy. This is the same class of problem the equal-cut guard just closed.

**Owner ruling (2026-11-15): raise the floor to 2 s.** Implemented: `sanitized` clamps to `2..3599` (and the `slowSeconds` gap clamp reads the same floor), the Settings field range is `2f..3599f`, and `Calibration.suggestCuts` now treats a target below `MIN_EASY_SECONDS = 2` as `AT_LIMIT`, so it cannot propose a 1 s cut that the config would silently snap back to 2. Tests: `sanitized lifts a one-second Easy cut to the floor of two` (idempotent) and the floor case in `CalibrationTest`.

---

### F9 — Sub-second answers can never satisfy the 200-row pace gate *(Minor, consequence of `5e91f1e`)*

**Evidence.** `gradedDwellSeconds` stores anything under 1 000 ms as 0 (`SpacedRepetition.kt:420-423`); `suggestCutsFromDwell` keeps only `elapsedSeconds > 0` (`Calibration.kt:314-321`). A learner whose genuine correct-answer pace is around one second stores 0 for the fastest half, so those rows never count toward `MIN_DWELL_ROWS = 200`; the card says "Not yet" indefinitely, and a mixed-pace learner's p10 is computed only from their slower answers, biasing the suggested Easy cut up.

**Why it matters.** The owner accepted the untimed convention, but its interaction with the gate is a dead end for exactly the fastest learners the suggestion is meant to serve. The commit message is explicit that the row "stays out of the answer-time suggestion", so this is a consequence to record, not a slip.

**Suggested fix.** Document the dead end in the card or README, or count sub-second rows as 1 s for the percentile only (never for grading).

**Owner ruling (2026-11-15): count sub-second rows as one second in the suggestion only.** Implementation keeps grading untouched — under a second is still stored as 0, still untimed, still `Good` — and changes only `suggestCutsFromDwell`: `elapsedSeconds == null` (no measurement, pre-v27) stays excluded, `elapsedSeconds == 0` now counts toward `MIN_DWELL_ROWS` / `MIN_DWELL_CARDS` / `MIN_DWELL_DAYS` and enters the percentile as `coerceAtLeast(1)`. The KDoc at `gradedDwellSeconds` no longer claims the row is dropped. Tests: `a sub-second answer counts toward the gate`, `a sub-second answer enters the percentile as one second` (21 × 0 s + 185 × 60 s ⇒ p10 = 31 s, where dropping the zeros gives 60 s and reading them as 0 gives 30 s), and `an unmeasured answer stays out of the answer-time pair`.

---

### F10 — Gate, clamp and Reset copy overstate or misname what happens *(Minor)*

- **The clamps can invert the quantile promise.** An all-1 s pace yields p10 = p90 = 1 → `fast = 2`, `slow = 7` (`Calibration.kt:343-352`), so every 1 s answer is Easy — 100 % Easy — while `strings.xml:824` promises "about a tenth of your correct answers fall on each side". The clamps are defensible; the unconditional promise is not. The test `answer times are clamped into a range worth naming` pins the 2/7 result, not the copy.
- **The days gate measures span-to-now, not coverage.** `now - min(reviewedAt) >= 14 d` (`Calibration.kt:323-337`) is satisfied by one old neutral row from a previous epoch, and passes for a learner who stopped six months ago. `evidenceDays` is that span, but `strings.xml:830` calls it "days of answer times".
- **The rows line counts only clock-neutral answers.** `strings.xml:828` says "correct answers"; `usable` also requires `correctnessOnly`, correct, unguessed, unskipped and `dwell > 0` (`Calibration.kt:314-321`). A learner switching *to* correctness-only with a long timed history sees "0 of 200 correct answers recorded" next to hundreds in the app, which reads as data loss.
- **Reset's dialog omits the epoch cost.** `reset_study_settings_confirm` (`strings.xml:526`) names "schedule knobs, target retention, ranking mode and new-card handling"; a reset that changes the pair writes a `manual` version and restarts the calibration sample (`RepositorySettings.kt:549-572`, README:2107). One clause would close it.
- **`AT_LIMIT` names the wrong wall for a STEP_UP.** `strings.xml:821` always blames the Easy cut's range; for `Direction.RAISE` the wall is `slowSeconds` (`Calibration.kt:277-289`).
- **`stats_cuts_from_times_same`** says the answers "agree with the current" pair under correctness-only, where that pair graded nothing. "Match" would be honest.

**Suggested fixes** are the copy corrections above, plus choosing whether the clamps or the tenth-promise wins.

---

### F11 — Nits

1. **Tint freshness claim is too strong.** `StudyViewModel.kt:201-203` says the tint and the stored grade "cannot disagree"; the arithmetic agrees (both use `gradedDwellSeconds`), but `liveDwellMillis` is published once a second (`:725-739`), so at a displayed 5 s an answer at 5.6 s can be tinted Good/Easy until the next tick. True of the rule, not of the snapshot.
2. **Test seams in production.** `StudyViewModel.freezeDwellForTests` (`:744-752`) and `AppDatabase.freshnessProbeThread` (`:159-165`, mutable static) are documented `internal`/`@VisibleForTesting` seams with no production caller. Harmless, worth a note.
3. **`recordConfigCuts` records an unsanitized row.** `GradingRulesStore.kt:62-98` compares and files the caller's config as passed, while `settings.setSchedulerConfig` sanitizes (`RepositorySettings.kt:408-423`). Production's only caller (`StudyViewModel` through `updateSchedulerConfig`) sanitizes first, so this is latent.
4. **`Calibration` and the aggregator disagree about 0 s.** Calibration treats `elapsedSeconds == 0` as untimed (`:316`); every other stats path treats a non-null value as timed and adds 0 (`StatsAggregator.kt:386,472-473,1141-1144`), so sub-second answers dilute mean dwell. Consequence of the new convention.
5. **The clock is pauseable** (`StudyViewModel.kt:666-680`): a learner can bank zero time while thinking and force Easy. Pre-existing, but `x` in δ1 = Easy − Good is now a measurement a learner can steer. Worth one KDoc line.
6. **`MigrationTest` comment vs assertion** — covered by F5.

---

## Prior-finding status

| Prior finding | Status | Evidence / caveat |
|---|---|---|
| **B1** epoch can start at 0 | **Fixed** | `StatsStore.kt:78-99` seeds through `gradingRules.currentVersion()` and fails loud (`GradingRuleUnavailable`) rather than reporting version 0; `McqRepository.kt:210-217`; `StatsSeedFailureTest`; `StatsRetentionStreamTest.openingStatisticsOnAMigratedV27InstallSeedsANewEpoch` |
| **B2** fresh-install file probe | **Fixed** | freshness is DB content (`AppDatabase.hasNoPapersOrReviews`, `:137-160`); `StartupTest` drives the real application; marker survives relaunch |
| **B3** any preference voids the seed | **Fixed** | `RepositorySettings.kt:382-390` short-circuits only on `correctness_only` and re-checks inside the edit; two store tests |
| **B4** Reset flips the rule | **Fixed** | `resetSchedulerConfig` never writes `correctness_only` (`:530-576`); `SettingsViewModelResetTest` pins UI + store |
| **M1** upgrade retires a tuned pair | **Fixed**, except F2 | `retainRetiredCuts` (`:487-495`); silently kept per owner; reset-first path skips it |
| **M2** chart hidden under correctness-only | **Fixed** in code; copy stale (F4) | `RetentionSection.kt:57-63`, `:254` |
| **M3** suggestion is a relative rule | **Owner decision**; feedback loop closed structurally | version-flag filter (`Calibration.kt:314-321`); salted test `CalibrationTest:565-584` |
| **M4** suggested pair unusable | **Fixed** | card persists under timed while `changes` (`StatsStore.kt:208-212`, `RetentionSection.kt:69-77`); values stay on screen |
| **M5** "not yet" states the wrong gate | **Fixed** | `Gate` enum and ordered checks (`Calibration.kt:229-240,335-338`); per-gate lines (`RetentionSection.kt:201-219`, `strings.xml:828-830`) |
| **M6** no e2e correctness-only test | **Fixed** | `StudyViewModelPullTest.correctnessOnlyGradingIgnoresANinetySecondDwell` + timed twin: 90 s → Good vs Hard, persisted `review_log` |
| **M7** stats test excluded the new fields | **Partly** — new branch is dead (F3) | `StatsRetentionStreamTest.kt:227-249` |
| **M8** v27 amended in place | **Accepted won't-fix**; hash unverified (F6) | owner decided no tagged release carried v27; no CI drift check |
| **M9** backup coverage | **Closed** | `correctnessOnly = true` round-trip (`BackupRestoreRoundTripTest.kt:228,323`); legacy `ladderVersions` + `ladderBlocks` decode (`:244-258`); legacy *streaming* import still untested |
| **M10** shared DataStore leakage | **Fixed** | `robolectric.properties` → `TestMcqApplication`; `SettingsResetRule` clears four keys before/after; classes that write prefs clear the store themselves |
| Minor 1 clock/check boundary | **Fixed** | tint from `gradedDwellSeconds(dwellMillis)` (`StudyScreen.kt:527-531`); ticker re-anchored per card (`StudyViewModel.kt:554-557`) |
| Minor 2 sub-second never Easy | **Fixed by decision**; consequences F9/F11.4 | `gradedDwellSeconds` (`SpacedRepetition.kt:420-423`); row-level test |
| Minor 3 equal cuts legal | **Fixed**; residual F8 | `sanitized` + `validExtra = it > fast`; `AT_LIMIT` |
| Minor 4 stale UI snapshot | **Fixed** | per-field handlers re-base from stored config (`SettingsViewModel.kt:393-410`) |
| Minor 5 four vocabularies | **Left by owner** | naming unchanged |
| Minor 6 migrated rows ≠ entity contract | **Open, accepted** | `AppDatabase.kt:876-882`; harmless because the epoch excludes them |
| Minor 7 reason/rule; step wording | **Partly** — reason/rule fixed; step wording open (F4) | `GradingRulesStore.kt:75-98`; `Calibration.kt:257` |
| Minor 8 restore drops unknown types | **Fixed, untested** | `RepositorySettings.kt:683-697` |
| Minor 10 empty titled card | **Fixed** | card constructed only when non-null (`RetentionSection.kt:69-77`) |
| D8 6/60 unsupported by shipped analysis | **Owner decision** (`5e91f1e` keeps 6/60); analysis deleted (F7) | rationale only in commit `bae14c5` |

---

## Behaviour → test map (current branch)

| Behaviour | Test | Status |
|---|---|---|
| Absolute cuts: 1–5 Easy, 6–59 Good, ≥60 Hard | `SpacedRepetitionTest` | ✅ |
| Clock bands agree with grade; first second neutral | `StudyDwellBandTest` | ✅ |
| Untimed (<1 s) never Easy; right → Good, wrong → Again | `StudyDwellBandTest`, `StudyViewModelPullTest.aTapInsideTheFirstSecondIsUntimedAndNeverEasy` | ✅ |
| Correctness-only through `StudyViewModel` → `review_log` | `StudyViewModelPullTest` 90 s pair | ✅ |
| Neutral clock tint under correctness-only | — | ❌ |
| Cut fields hidden while the switch is on | — (UI test only waits for the section) | ❌ |
| Correctness-only `RetentionData` payload (dwell pair, null contrast) | `StatsRetentionStreamTest` | ⚠️ dead branch (F3) |
| Forecast chart stays under correctness-only | — | ❌ |
| Suggestion visible under timed while pair differs | `StatsRetentionStreamTest` (field only) | ⚠️ no render test |
| `suggestCutsFromDwell` percentile/gates/clamps | `CalibrationTest` | ✅ |
| `suggestCuts` keep/step/cap/limit/direction | `CalibrationTest` | ✅ |
| Fresh install seeds correctness-only | `GradingRulesStoreTest`, `RepositorySettingsTest`, `StartupTest` | ✅ |
| Upgrade is not seeded; restored rule wins | same | ✅ |
| Reset keeps the rule and mint rules | `SettingsViewModelResetTest`, `GradingRulesStoreTest` | ✅ |
| RULE vs MANUAL versions; no-op save; crash repair | `GradingRulesStoreTest` | ✅ |
| Epoch seeds on migrated install; failure fails loud | `StatsRetentionStreamTest`, `StatsSeedFailureTest` | ✅ |
| Migration 25→27 / 26→27 | `MigrationTest` | ✅ |
| 26→27 non-zero `ladderVersion` resolves | — | ❌ (F5) |
| Backup round-trip incl. `correctnessOnly = true` | `BackupRestoreRoundTripTest` | ✅ |
| Pre-v27 backup decode with `ladderBlocks` | `BackupRestoreRoundTripTest` | ✅ (json decode only) |
| Pre-v27 backup through the **streaming** reader | — | ❌ |
| Restore keeps the calibration evidence | — | ❌ (F1) |

**Test infrastructure.** `HangWatchdogRule` bounds and stack-dumps a hung test and halts the worker rather than wedging CI; `RobolectricMainThread` runs DB work off the paused main looper while pumping it; `robolectric.properties` makes `TestMcqApplication` (no startup probe) the default; `SettingsResetRule` clears `correctness_only`, `grading_rules_version`, `ladder_version` and the install marker before and after each test. The M10 leak is closed. The new `testLogging` block in `app/build.gradle.kts` makes a hang visible.

**Coverage gaps that matter:** the three UI behaviours (tint, hidden fields, chart) and the correctness-only stats payload are the ones a regression could silently break without any CI test failing.

---

## Docs, copy and release review

- **README/strings/KDoc drift:** F4. The copy was true before `564497d`/`5641fd2`; it is now false in five README places, two strings, the readiness model KDoc and the PR body.
- **Deleted planning docs:** F7. 80 `plan §N` citations, `ladder-evaluation.md` still describing the deleted ladder, dead PR links, audit record removed.
- **Release compliance:** good. Feature branch off `main`; PR #81 is a draft; no `versionName`/`versionCode` change (both derive from the release tag, `app/build.gradle.kts:101-104`); no workflow change; debug/release variants and artifact names unchanged. The 3-line `app/build.gradle.kts` change is test logging only.
- **Commit-message accuracy:** `564497d` claims the new stats assertion pins the rule in force, but its correctness-only half cannot run (F3). `5e91f1e` accurately records the owner decisions. `a62a94c` is labelled "legacy docs deleted" but also deletes the current `audit.md`.
- **CI tip coverage:** `a62a94c` is docs-only and unbuilt; the last built commit is `5e91f1e`. Low risk, but the merged commit should be the built one, or the docs-only diff must be confirmed to change nothing else.

---

## Strengths worth recording

- **The feedback loop is closed structurally, not by convention.** A row carries its *version's* rule flag, not the current setting, and the test salts a clean 200-row set with 100 tinted rows (`CalibrationTest:565-584`). That is the right shape for this invariant.
- **One rounding function** feeds both the live tint and Check, so the half-second disagreement is impossible by construction, and the untimed convention is consistent across grade, band and evidence filter.
- **One writer, one lock.** Every cut/version write goes through `GradingRulesStore`'s mutex and `McqRepository.schedulerWriteMutex`; `writeCuts` is a single DataStore edit with the row insert after it; the crash window is repaired idempotently.
- **Stats fails loud.** `requireConfig` throws rather than returning a version-0 report; `configWithEpoch` refuses an `effectiveFrom` from a missing row. The worst outcome is explicitly named and avoided.
- **Percentile arithmetic is integer, half-up, hand-checkable**, and the clamp algebra is total: no input can emit a pair with `fast >= slow`, an Easy cut under 2 s or a Hard cut over 120 s.
- **Wire compatibility is deliberate and tested.** The `ladderVersions` wire key is kept, `ladderBlocks` is skipped, unknown keys are ignored, and both are pinned by fixtures.
- **Test infrastructure** (watchdog, main-thread pump, global test application, reset rule) is a real improvement over the previous shared-state race.
- **Repo hygiene:** feature branch, draft PR, no release-state change, emulator suite still runs in CI.

---

## Owner rulings (2026-11-15)

All six decisions were made in favour of the recommended option.

| # | Ruling | Consequence |
|---|---|---|
| **F1** | **Fix** — reuse the restored version row and its `effectiveFrom` when the restored pair matches; mint only when it differs or the row is missing | Code + test: a restored review inside the backup's epoch must be counted by `Calibration.report` |
| **F2** | **Fix** — call `retainRetiredCuts()` on the reset-first path | Code + test |
| **F3** | **Add the fixture** — the correctness-only branch must execute in CI | Test only |
| **F4** | **Match the words to the app** — correct README, `strings.xml`, model KDoc and PR body; keep the chart visible under correctness-only | Copy (owner-approved) |
| **F5** | **Add the assertion** — a non-zero `ladderVersion` must resolve after 26→27 | Test only |
| **F6** | **Regenerate** `27.json` with a build; commit only if it differs; no 27→28 | Build + schema file |
| **F7** | **Delete the obsolete documents** — `ladder-evaluation.md` removed; `audit.md` kept until release | Docs |

### Later rulings (2026-11-15, after the first release review)

| # | Ruling | Consequence |
|---|---|---|
| **F8** | **Raise the Easy-cut floor to 2 s.** `sanitized` allowed `fast = 1`, and `inferGrade` needs `dwell in 1 until fast`, so a 1 s cut left the Easy class permanently empty: `contrast1` null, the cut suggestion stuck at `NEEDS_EVIDENCE` for good. | Code, UI range, copy and tests |
| **F9** | **Count sub-second answers as one second in the answer-time suggestion only.** Grading is untouched (under 1 s stays untimed and `Good`), but the row now counts toward the 200/30/14 gates and enters the percentile instead of being dropped, so a fast learner's p10 is no longer biased upward and the gate is reachable. | Code, copy and tests |
| **R2 follow-up** | **Invalidate the calibration cache when reviews are deleted.** The key's freshness guard only notices appends, so a cached report could keep counting deleted reviews until the block advanced. | Code and test |


**Residual accepted after the rulings:** the 80 `plan §N` code-comment citations (code, not documents), and the items the owner previously decided to leave (`M3` pace suggestion, `M8` no 27→28, naming). `F8` (`fast = 1`) was not part of the ruling set and is not in this batch.

## Suggested order of work

1. ~~Owner ruling on F1 and F2~~ — **done 2026-11-15**.
2. **Implementation subagent (one scoped batch):** F1 fix + test, F2 one-liner + test, F3 and F5 fixtures, F4 `strings.xml` copy, F6 schema regeneration.
3. **Copy pass (supervisor):** README, model KDoc and PR body (F4).
4. **Docs pass:** `ladder-evaluation.md` deleted (done); keep this audit record until release.
5. **Schema:** F6 build/drift check (inside the implementation batch).
6. **Re-review:** three specialist reviewers on the resulting diff, then build the docs-only tip and un-draft.

---

## Post-ruling implementation (2026-11-15)

The approved batch was implemented by one scoped implementation subagent and reviewed by the
supervisor. Working tree: 10 modified files, `ladder-evaluation.md` deleted, `audit.md` new.
No commit, no push.

| Finding | Change | Where |
|---|---|---|
| **F1** | `seedAfterRestore()` deleted. `settingsRestore` now calls `gradingRules.currentVersion()`, so a restored id whose row exists **with the same pair and a usable timestamp** keeps the id **and** its `effectiveFrom`; a missing row is repaired in place under the same id; a mismatched pair or an unusable stamp (≤ 0 or in the future) mints a new `prior` row; a pre-v27 backup also mints one. | `GradingRulesStore.kt`, `McqRepository.kt`, `StatsStore.kt` |
| **F2** | Inside the existing reset edit, when `!cutsCurrent`, the stored `fast_seconds`/`slow_seconds` are copied into `retired_easy_seconds`/`retired_slow_seconds` before the defaults overwrite them. No second or nested edit. | `RepositorySettings.kt` |
| **F3** | `StatsRetentionStreamTest.correctnessOnlyPayloadCarriesTheAnswerTimePair` switches the rule through `updateSchedulerConfig` first, so the correctness-only payload is now exercised. | test |
| **F4** | `strings.xml` (8 strings incl. a new `stats_cuts_limit_hard`), `RetentionSection.kt` (`AT_LIMIT` picks the wall from `contrast1.direction`), `StatsModels` KDoc. | res + UI + KDoc |
| **F5** | v26 fixture gains a review stamped `ladderVersion = 7`; the test asserts it reads back as 7 and that `version(7)` exists with 8/30. | `MigrationTest.kt` |
| **F6** | `27.json` deleted and regenerated by Room; the build reproduced `identityHash c8196a592389f537cf16f3dca4c1c7e3` and a **byte-identical** file. The hand edit was correct; nothing to commit. | schema |
| **F7** | `ladder-evaluation.md` deleted; `audit.md` retained until release; `plan §N` citations remain as accepted residual. | docs |

### Executed verification on the repaired host

The host's APK toolchain was rebuilt outside `/sdcard/repo/apk-build` (that folder stays common
with the Alpine machine) so the paths `env.sh` already exports are real again: JDK 17 at
`/usr/lib/jvm/java-17-openjdk`, SDK at `/opt/android-sdk` (platform 37.0, build-tools 36.0.0 and
37.0.0, platform-tools), an x86-64 glibc sysroot at `/opt/android-aapt2-sysroot`, and a
`qemu-x86_64` wrapper at `/opt/android-aapt2/aapt2`.

| Check | Result |
|---|---|
| `aapt2 version` through the wrapper | `2.20-15087165` |
| `:app:processDebugResources` with the override | BUILD SUCCESSFUL |
| `:app:assembleDebug` on the final tree | BUILD SUCCESSFUL, 26 MB `app-debug.apk` |
| `:app:compileDebugUnitTestKotlin` | BUILD SUCCESSFUL |
| Pure-JVM suite (`run-jvm-tests.sh`) | **165 classes, 1480 tests, 0 failures, 2 skipped** |
| `27.json` regenerated from scratch | byte-identical to the committed file |

Robolectric/SQLite/Compose classes (including `GradingRulesStoreTest`, `StatsRetentionStreamTest`
and `MigrationTest`) still cannot run on this aarch64 host; CI remains authoritative for them.

---

## Second-pass review (2026-11-15)

Reviewer 1 of 3 (design/domain) read the working tree and traced
`settingsRestore → currentVersion → ensureVersionLocked → writeCuts → StatsStore.configWithEpoch
→ Calibration.report`, the reset edit and `Calibration.suggestCuts`.

**No blocking issue.** The three candidate defects it chased — a stale `ladder_version`, pooling
across `correctness_only`, and an export that is not a single instant — were each rejected as
unreachable in production, with the trace given.

| # | Finding | Severity | Disposition |
|---|---|---|---|
| R1 | A restore reuses an existing row **by id only**, not by pair. A settings-only restore, or any local row at that id holding a different pair, keeps a timestamp that never graded the restored cuts — and `Calibration.report` filters purely by time, so foreign rows enter the epoch. A row with `effectiveFrom <= 0` or a future stamp (wrong device clock) has the same effect: 0 pools the entire log, a future stamp freezes `epochDays` at 0. | **Major (new)** | Fix: keep the timestamp only on an exact pair match with a usable timestamp; otherwise repair in place. Test added. |
| R2 | `StatsStore.calibrationCache` is keyed on the grading version and epoch, which a restore now keeps stable, so a restore can serve a report built from pre-restore rows. | Minor | Fix: clear the cache in `settingsRestore`. |
| R3 | `stats_cuts_limit` says "bottom of its allowed range", but `AT_LIMIT` fires whenever `fast - step < 1` with `step` up to 4, so at 2 s/60 s the cut is not at the floor. | Minor | Fix copy. The RAISE variant is literally true and stays. |
| R4 | `reset_study_settings_confirm` says the sample restarts only "if this changes the cut pair"; the retired-pair reset keeps 6/60 yet still moves the epoch. | Minor | Fix copy. |
| R5 | `GradingRulesStore`'s class KDoc omits the pre-v27 retirement branch from its list of write triggers. | Nit | Fix KDoc. |
| R6 | `aPreV27RestoreStillMintsAPriorVersion` claims the old pair is retired but never asserts the retired keys. | Minor | Add assertion. |
| R7 | The "capture the retired pair" logic is duplicated between `retainRetiredCuts` and the reset edit. | Nit | Optional refactor to one helper. |
| R8 | The backup export reads settings and the study log at two different instants; a restore now trusts that they agree. No reachable contamination (the only writers sit behind a nav-scoped Settings screen), but the invariant is now load-bearing and unstated. | Nit | Comment at the export site. |

Verdicts recorded: F1 **correct, with the R1 caveat**; F2 **correct**; the evidence contract
**correct, with the single-instant precondition**; the `AT_LIMIT` split **correct, with the R3
wording caveat**.

### Second-pass review, reviewer 2 (implementation/integration)

Ran its own `assembleDebug` + `compileDebugUnitTestKotlin` (forced recompile, 23 tasks executed)
on the batch-1+2 tree: **BUILD SUCCESSFUL**, warnings only. All seven scoped items came back
correct; no blockers.

| # | Finding | Severity | Disposition |
|---|---|---|---|
| I1 | A cut save **during a backup export** produces a backup whose `settings.json` (pair, version) and streamed `grading_versions`/`review_log` disagree. `busy` only disables `BackupDataSection`, so `StudySchedulerSection` stays live. On restore the new pair guard passes — the settings pair matches row *V* — while the newer labels (graded under *V+1*) sit inside the epoch window, so they are counted as evidence for *V*'s cuts. Reviewer 1 called this unreachable; reviewer 2 showed the writer lives on the same screen as the export button. | **Major (new)** | Fix: make the export refuse to produce a file when the settings moved between snapshot and stream (or hold the write mutex). |
| I2 | `ensureVersionLocked` reads the version id and the pair from **two** DataStore snapshots, so a restore interleaved with a read can pair the pre-restore id with the post-restore pair and spuriously re-epoch. | Minor | Fix: use `SchedulerConfig.gradingVersion` from the single snapshot. |
| I3 | `StatsStore`'s "every other writer moves the key" is false — deleting a paper/question/category removes rows without moving `maxReviewedAt`. | Minor | KDoc correction; deletion invalidation is a pre-existing gap, recorded as follow-up. |
| I4 | Two KDocs overstate the restore: the "usable start" sentence is false for the missing-row branch, and the `McqRepository` KDoc still describes a "settings-only restore" that does not exist (every restore wipes and replays all tables). | Nit | KDoc correction. |
| I5 | `strings.xml` says Easy applies "from one second up", false when the Easy cut is 1 s (F8). | Minor | Reword without documenting the `fast = 1` degeneracy. |
| I6 | `settingsRestore` still returns an `Int` no caller reads. | Nit | Retag `Unit` if nothing consumes it. |

Confirmed correct by that pass: the exact-match restore path writes nothing; `insertVersion` is a
bare `@Insert` (ABORT), so a same-id insert fails loudly and cannot silently replace history;
both invalidations sit in a non-suspending `finally`; `calibrationCache` is a
`ConcurrentHashMap` written from two dispatchers, so the new accessor needs no lock; the
`retainRetiredCutsIfAbsent` refactor is behaviour-identical and still runs inside the reset edit;
the `AT_LIMIT` branch is reachable only with a non-null contrast and only under timed grading.

### Third batch (reviewer 2 findings)

| # | Fix |
|---|---|
| I1 | `GradingGuard` (cut pair, ladder id, rule, as `settings.json` carries them) plus `McqRepository.settingsUnchangedSince`. Both exports take the snapshot, stream, then re-read; a moved guard deletes the file and fails with `BACKUP_RULE_MOVED` ("… the grading settings changed while the backup was being written … the file was discarded — try again."). The document is deleted through `DocumentsContract.deleteDocument`, since a `DocumentsProvider` URI ignores `ContentResolver.delete`. Every writer mints a new ladder id, so even a save-and-revert moves the guard. |
| I2 | `ensureVersionLocked` reads the id and pair from one `SchedulerConfig` snapshot; the second read and the trailing re-read are gone. |
| I3–I4 | `StatsStore`, `GradingRulesStore` and `McqRepository` KDocs corrected: the key moves on appends (deletions are a recorded follow-up), the "usable start" sentence is scoped to rows that exist, and the non-existent "settings-only restore" wording is replaced by "defence in depth against a backup whose settings and log disagree". |
| I5 | `easy_answer_threshold_help` no longer claims Easy applies from one second up. |
| I6 | `settingsRestore` returns `Unit`; no caller or test consumed the `Int`. |

### Reviewer 3 (tests / copy / docs / release) — run by the supervisor

The subagent could not start (provider quota exhausted), so I ran this pass directly against the
tree, the same substitution recorded for reviewer T in the first audit.

- **Test adequacy.** No vacuous assertion found in the new tests. The four F1 tests each fail if
  their branch regresses: the exact-match test counts rows before and after (2, unchanged) and
  pins the restored `effectiveFrom`; the mismatch test asserts a *different* id with
  `effectiveFrom >= restoredAfter` **and** that row 5 keeps 4/20 and its old stamp; the timestamp
  test loops over `0` and a future instant and asserts a positive stamp not after now; the
  pre-v27 test asserts the retired keys, which the upgrade path is the only writer of. The cache
  test loads once (1 pair), deletes the oldest row, restores again and requires 0 — it would pass
  vacuously if the cache were not read at all, so it is checked against `StatsStore`'s
  `calibrationCache` lookup, which is on that path. The migration test resolves `version(7)` and
  pins 8/30, so a dropped copy fails it. `MigrationTest`'s new review names a question that does
  not exist; harmless (`review_log` has an FK on `paperId` only) but noted.
- **Copy.** Placeholders and call sites match for every changed string (`stats_cuts_from_times_same`
  takes fast, slow, evidence in that order; both `stats_cuts_limit*` take fast, slow; the three
  gate lines take the measured value and the constant). Only `values/` exists, so no locale is
  stale. The 5 pp step, the 2 s / 5 s / 120 s clamps, the 200/30/14 gates and the first-second
  rule all match the constants.
- **Docs.** No reference to a deleted document survives outside `audit.md`. `README.md` matches
  the code after one supervisor fix: line 2078 still said Easy applies "from one second up",
  which is false while the Easy cut is 1 s, and was corrected in the same pass as the string.
- **PR body.** Rewritten: the two deleted files are no longer cited, the calibration card is
  described as kept, the upgrade note no longer claims the cuts are kept, the step is "5 pp of
  contrast", and the test count is 165 classes / 1 480 tests instead of "326 classes".
- **Release state.** No `versionCode`/`versionName`, workflow, signing, Gradle or artifact-name
  change; no new dependency. Branch is a feature branch; PR #81 is still a draft.

### Fourth batch — the two later rulings

| # | Fix |
|---|---|
| F8 | `SchedulerConfig.sanitized` floors the Easy cut at 2 s (`slowSeconds`' gap clamp reads the same floor); the Settings field range is `2f..3599f`; `Calibration.suggestCuts` treats a target below the new `MIN_EASY_SECONDS = 2` as `AT_LIMIT`, so it cannot propose a cut the config would snap back. A stored 1 converges to 2 in one pass and a second `sanitized()` is a no-op. |
| F9 | `suggestCutsFromDwell` keeps `elapsedSeconds != null` and now includes `0`, counting it toward all three gates and entering the percentile as one second. Grading, the stored value and every other statistics path are untouched. |
| R2 follow-up | `statsStore.invalidateCalibrationCache()` runs after the store call returns in `deletePaper`, `deleteCategory`, `deleteQuestion`, `deleteQuestions` and `moveQuestionsToCategory` — the five funnel points that remove `review_log` rows. A rolled-back store call invalidates nothing. |

New tests: `a sub-second answer counts toward the gate`, `a sub-second answer enters the
percentile as one second`, `an unmeasured answer stays out of the answer-time pair`,
`sanitized lifts a one-second Easy cut to the floor of two`, the `AT_LIMIT`/`STEP_DOWN` floor
cases, and `aDeletionDoesNotServeTheReportBuiltFromDeletedReviews`.

### Executed evidence after the third batch
| Check | Result |
|---|---|
| `:app:assembleDebug` (batch 3 tree) | BUILD SUCCESSFUL |
| Pure-JVM suite | **165 classes, 1 480 tests, 0 failures**, 2 skipped |
| Pure-JVM suite (batch 4 tree) | **165 classes, 1 484 tests, 0 failures**, 2 skipped |
| Full `:app:testDebugUnitTest --rerun-tasks` (all 262 classes) | 632 failures, **all 81 failing classes Robolectric/SQLite** (`The Robolectric native runtime is not supported on Linux (aarch64)`); **0 pure-JVM classes fail** |
| CI on `18f55e2` (run 43678004818) | **green** — wrapper-validation, build, release-build, ui-test |
| `27.json` regenerated from scratch | byte-identical, `identityHash` unchanged |

---

*Findings marked "verified" in the text were re-checked against the tree during synthesis. Robolectric/SQLite/Compose claims are static until CI runs; CI at `5e91f1e` predates this batch, so the next CI run on the branch is the first executed evidence for these changes.*
