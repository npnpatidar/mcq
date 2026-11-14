# Audit — `feature/absolute-dwell-thresholds`

- **Range reviewed:** `7f47596..783e68f` (8 commits, 42 files, +4629 / −3011)
- **Head:** `783e68f chore(ui): clarify the correctness-only workflow in the app copy`
- **Base:** `7f47596 delete legacy files`; PR **#81** is open as a **draft**, cut from `main`
- **Method:** three independent read-only reviews — (D) design & domain model, (I) implementation & integration, (T) tests, copy, docs & release — followed by a verification pass on the highest-severity claims
- **Not verified locally:** this host is ARM64, so no Robolectric/SQLite test, build, or Compose runtime behaviour was executed. Test judgement is static, checked against production code. CI is authoritative: tip `783e68f` is green in run `43492867486`; `cf153c7` (`43477780942`) and `687dc2a` (`43481119687`) were red.

**Verdict: do not un-draft yet.** The core idea is sound and the pure-function layer is well tested, but four defects undermine the feature's own premise (that the learner's dwells are measured while the clock makes no claim), and the bridge from "suggested pair" to "applied pair" does not close. Most fixes are small and local. No migration or FSRS maths defect was found.

---

## Severity summary

| Severity | Count | Themes |
|---|---|---|
| Blocker | 4 | Epoch seeding, fresh-install detection, seed swallowed by any preference, Reset flips the rule |
| Major | 10 | Upgrade data handling, hidden calibration chart, relative-rule suggestion, self-defeating UX, test oracle gaps, v27 amendment, test isolation |
| Minor | 10 | Boundary semantics, degenerate config, naming, migration fidelity, copy precision |
| Nit | 5 | Dead code, stale comments, doc housekeeping |

---

## Blockers

### B1 — The grading epoch can start at 0, pooling pre-v27 reviews into the new rule
*(D1; verified)*

`StatsStore.kt:159-163` derives the epoch from `config.gradingVersion`, and `versionStart` is `dao.version(version)?.effectiveFrom ?: 0L`. All ten `StatsStore` call sites read `settings.schedulerConfigNow()` — e.g. `StatsStore.kt:91` — and never `gradingRules.currentVersion()`. That seeding lives only behind `McqRepository.schedulerConfigNow()` (`McqRepository.kt:167-170`).

So on a fresh v27 install (or upgraded install) where Statistics is opened **before** the first study session or Settings save, the version has no history row, `epochStart` becomes `0`, and the report pools rows graded under the retired `8 + b` / `30 + b` allowances with rows graded under absolute 6/60. `suggestCuts` then proposes a step from contaminated labels — exactly the invariant the redesign exists to protect.

**Fix:** seed in `settings.schedulerConfig()`/`schedulerConfigNow()` itself, or make every reader go through `gradingRules.currentVersion()`. A v27 install must never observe version 0.

### B2 — "Fresh install" is inferred from a database file, so the default rule often does not apply
*(I2; verified)*

`McqApplication.kt:27`: `freshInstall = !getDatabasePath(AppDatabase.NAME).exists()`.

The DB is created by first-launch library access (paper list, import, a test), while the seed only runs on the first `schedulerConfig()` read. Launch → browse or import → process killed → relaunch ⇒ the file exists ⇒ classified as an **upgrade** ⇒ timed grading, silently. The release note's promise ("a new install starts on correctness-only") fails on an ordinary path.

**Fix:** persist a one-shot `install_seeded` marker, or treat "DB exists but no papers and no review rows" as fresh.

### B3 — Any unrelated preference permanently disables the fresh-install seed
*(I3; verified)*

`RepositorySettings.kt:355`: `if (snapshot.asMap().isNotEmpty()) return`.

Changing the theme, font scale, haptics, or starting a test (writes `last_test_length`) before the first scheduler read voids the seed. This is a deliberate "any preference means restore" heuristic — `RepositorySettingsTest.aRestoredInstallKeepsTheRuleInItsBackup` pins it — but it cannot distinguish a restore from a learner tapping a switch.

**Fix:** decide freshness from the DB (no papers, no reviews), not from "DataStore is untouched".

### B4 — "Reset" silently switches a new install back to timed grading
*(I4; verified)*

`RepositorySettings.kt:466`: `prefs[correctnessOnlyKey] = c.correctnessOnlyGrading`, where `c` is the defaults (`false`), while the confirm dialog (`strings.xml:526`) mentions only "schedule knobs, target retention, ranking mode and new-card handling".

A learner who taps Reset mid-experiment switches to timed grading, opens a new `prior` version, and the unbiased dwell sample is thereafter mixed with tinted dwells. `GradingRulesStoreTest.aResetWritesItsOwnVersion` does not assert the rule.

**Fix:** leave `correctness_only` out of the reset (it is a rule, not a knob), or change the dialog copy; assert the outcome in the test either way.

---

## Majors

### M1 — An upgraded learner's tuned pair is replaced with no notice, and the PR body says the opposite
*(I5, T3, D12; verified)*

`RepositorySettings.kt:302-330` returns 6/60 when the version gate is absent, then `GradingRulesStore.kt:136` immediately calls `settings.writeCuts(6, 60, …)`, overwriting `anki_fast_seconds`/`anki_slow_seconds`. The old value is not retained anywhere.

`threshold.md` §6 calls this a deliberate one-time reset, which is defensible; the **PR #81 description** says "an upgrade keeps the learner's current rule **and cuts**", which is false. There is no in-app notice either (`strings.xml` has none), so the only disclosure is that the fields now read 6/60. A downgrade, or a Settings-only restore of a pre-upgrade backup, is the only recovery.

**Fix:** correct the PR/release text; keep the old pair under a `legacy_*` key or record it as a `legacy` version row; add one sentence to the cut help.

### M2 — The forecast-calibration chart disappears for every new install
*(I1; verified)*

`RetentionSection.kt:61`: the whole `CalibrationCard` is gated on `bundle.timeBasedGrading`. But that card carries two independent things — `CalibrationChart` + Brier summary (`RetentionSection.kt:187-219`), which remain meaningful under correctness-only because `calibration.samples` keeps counting, and `CalibrationEvidence`, which is about cuts.

Since correctness-only is the default, most v27 users lose the app's only predicted-vs-actual view, with no note.

**Fix:** always render the chart and summary; hide only the cut contrast, readiness gate and suggestion.

### M3 — The shipped suggestion is a relative rule re-entering through Statistics
*(D3)*

`Calibration.kt:280-325` uses `DWELL_EASY_PERCENT = 10` / `DWELL_HARD_PERCENT = 90` and recommends those quantiles as the next absolute pair. A learner who becomes uniformly faster still gets 10 % Hard by construction — the adaptive-ladder logic the branch deletes, and which `threshold_analysis.md` rejects.

Percentiles are also computed per row without card clustering: 200 rows over 30 cards is ~6.7 answers per card, and dwell is strongly card-dependent, so the effective *n* for a 10th percentile is nearer 30. `Calibration.contrast` next door does a card-clustered bootstrap; this path does not.

**Fix:** dedupe/weight per card before percentiling; either drop the 10 %-Hard target or label it explicitly as a pace quantile, not a retention target.

### M4 — The suggested pair is unusable: following the instruction destroys the number
*(D4, T4; verified)*

`strings.xml:824` says "Turn the correctness-only switch off in Settings, then set the two thresholds to these." But `RetentionSection.kt:61-68` shows `DwellCutsCard` only while `timeBasedGrading == false`, so flipping the switch replaces the card before the learner can read the values. They must memorise two integers across two screens and can never re-check them.

**Fix:** keep the dwell suggestion visible under timed grading when the applied pair differs, or make the row tappable to prefill Settings.

### M5 — The "not yet" line can state an impossible requirement and the wrong blocker
*(D10, I9, T5)*

`RetentionSection.kt:156-161` renders `stats_cuts_from_times_not_yet(evidence, MIN_DWELL_ROWS)` — "Not yet: %1$d of %2$d correct answers recorded, over at least 14 days." There are three gates (`Calibration.kt:300-305`): rows, distinct cards, days. With 250 answers on 12 cards the learner sees "Not yet: 250 of 200 correct answers recorded". "14 days" is hardcoded rather than derived from `MIN_DWELL_DAYS`.

**Fix:** carry the failing gate in `CutSuggestion` and render one line per gate from the constants.

### M6 — No end-to-end test of correctness-only grading
*(T2)*

`StudyViewModelPullTest` asserts only ≈0 s → Good, which holds under both rules; `StudyViewModel` could pass the timed `SchedulerConfig()` to `autoGrade` and every test still passes. The `@After` calls `repository.resetSchedulerConfig()`, which flips the rule to timed after the first test, masking the gap. The feature's headline path (switch → grading → persisted row) is untested; only the pure function is.

**Fix:** one case with `correctnessOnlyGrading = true` and a 90 s dwell asserting `GOOD`, plus a timed case at 90 s asserting `HARD`.

### M7 — The only integration test explicitly ignores the new Statistics fields
*(T1)*

`StatsRetentionStreamTest.kt:158-170` copies `calibrationReport = null, cutSuggestion = null, dwellSuggestion = null` and then asserts only `calibrationReport != null`. Nothing asserts that `StatsStore.kt:131-144` ever populates `cutSuggestion`/`dwellSuggestion`. This is the test that went red on `cf153c7`, and it was fixed by removing the new fields from the oracle rather than adding a positive assertion.

**Fix:** assert `cutSuggestion != null` in the timed fixture and `dwellSuggestion != null && cutSuggestion == null` in a correctness-only fixture.

### M8 — v27 was amended in place, so intermediate CI installs cannot be upgraded
*(I6, I7)*

`AppDatabase.kt:739-763`: 26→27 originally created `grading_versions` without `correctnessOnly` (`bae14c5`); `687dc2a` edited the same migration to add the column. Any device that installed `app-debug`/`app-release` from those CI builds (`a4b8535` records them) now has a v27 file without the column and no 27→28 path — Room will throw on open. Released users (v26) are fine. Separately, `27.json` was hand-edited in `687dc2a`, including its `identityHash` (`d9d05…` → `c8196…`), so nothing guarantees it matches generated output until a build regenerates it.

**Fix:** either bump to 28 with a `27→28 ALTER TABLE`, or state clearly that only CI artifacts of this branch are affected; run one build and confirm `27.json` is byte-identical (`git status` clean).

### M9 — Backup compatibility is asserted but not tested, and the new field is only ever `false`
*(T7, I12)*

`BackupRestoreRoundTripTest.kt:212-230` inserts `GradingVersionEntity(reason = "manual")` and relies on the default `correctnessOnly = false`. No test anywhere mentions `ladderBlocks` or `ladderVersions`, despite `Backup.kt:450` skipping blocks and `Backup.kt:239` aliasing the version key. Deleting `LadderBackupStreamingTest` removed the last streaming coverage of that path, yet both the PR body and `threshold.md` §4.4 claim old backups still import.

**Fix:** seed a `correctnessOnly = true` version; add a hand-written v26 `study.json` with `ladderVersions` + `ladderBlocks` and decode it through both `read` and `readStreaming`.

### M10 — Shared DataStore state leaks between tests, in exactly the way `db4b426` documents
*(T6)*

`db4b426`'s fix removes `grading_rules_version` *inside one test*. The shared `@After restoreDefaults()` never removes it, and `writeCuts` re-stamps it (`RepositorySettings.kt:427`); `StudyViewModelPullTest` captures `freshInstall=true` for the whole JVM, so clearing prefs mid-run re-seeds the rule. Only `GradingRulesStoreTest` clears the store per test.

**Fix:** clear `grading_rules_version` and `correctness_only` in a shared `@Before`, or give `SettingsStore` an injectable DataStore.

---

## Minors

1. **Clock and Check disagree in the last half-second of each band** *(D5)* — `StudyViewModel.kt:203-211` truncates `… / 1000L` for the tint; `gradedDwellSeconds` (`SpacedRepetition.kt:409-410`) rounds half-up. At 5.5–5.99 s the clock is green but Check grades Good; at 59.5–59.99 s amber but Hard. The ticker (`:709-718`) is also anchored at ViewModel init, not card start, adding up to another second of lag. `StudyUiState:106-108` and `StudyDwellBandTest` claim the two can never disagree. *Fix:* derive the live band from `gradedDwellSeconds(questionElapsedMillis)`; anchor the ticker at card start.
2. **A sub-half-second answer can never be Easy, and the "untimed" convention is now unreachable** *(D2)* — `SpacedRepetition.kt:341`: `dwellSeconds in 1 until fastSeconds`. A 400 ms answer rounds to 0 and grades Good. The only caller always measures, so 0 no longer means "untimed", yet `Calibration.kt:293` still filters `> 0`, dropping the fastest answers from p10 and biasing the Easy class (and δ1) downward. *Fix:* compare milliseconds against `cut * 1000`, or carry an explicit `timed` flag.
3. **`fastSeconds == slowSeconds` is legal and permanently degrades the report** *(D6)* — `SpacedRepetition.kt:225` (`coerceIn`) and `StudySchedulerSection.kt:374` (`>= fastSeconds`) both accept equality; the Good class becomes empty and contrast is null forever, leaving `NEEDS_EVIDENCE` with no explanation. *Fix:* require `slow > fast`, or surface the degenerate pair.
4. **A stale UI snapshot can revert a cut and mislabel the rule** *(I8)* — `SettingsViewModel.kt:344-354` re-bases only `fsrsParameters` from stored config; cuts and the rule come from the composable snapshot, so editing fast then slow within one DataStore emission writes the first edit back and records an extra MANUAL version. *Fix:* re-base cuts and rule from `stored` the way weights already are.
5. **Four vocabularies for one concept** *(D11)* — `Entities.kt:356-357` pairs `easySeconds` with `slowSeconds`; the config pairs `fastSeconds` with `slowSeconds`; `DwellBand`, `GradeClass` and `ReviewGrade` are three overlapping lattices; `timeBasedGrading` inverts `correctnessOnlyGrading` in the same payload.
6. **Migrated rows do not satisfy the entity contract** *(D12)* — `MIGRATION_26_27` copies v26 cuts verbatim, but those were nominal cuts with the allowance added on top (`8 + b`), so the copied pair did not grade the row and `correctnessOnly = 0` cannot distinguish the v26 era from the v27 absolute era. Harmless only because the epoch excludes them.
7. **`reason`/`rule` and the 5 pp step are documented wrongly** *(D13)* — `GradingRulesStore.kt:75` adds `rule` to `reason`; `threshold.md:53` lists only `prior`/`manual`. A save changing both cut and rule records `manual`. The step is `abs(value) / 0.05`, i.e. total deviation, while `README.md:1001` says "one second per 5 pp **outside** the band".
8. **Restore clears every preference but skips unknown value types** *(I10)* — `RepositorySettings.kt:534` (`else -> continue`) vs `:558` (`prefs.clear()`); a future `stringSetPreferencesKey` would be silently destroyed.
9. **Boundary tests only cover whole seconds** *(T10)* — `gradedDwellSeconds` ↔ `inferGrade` is never composed at 5 499/5 500/59 499/59 500 ms, the values that actually flip a grade.
10. **A titled, empty card is renderable** *(I13)* — `DwellCutsCard` returns early when `suggestion == null` but still draws `ChartCard` chrome; production always supplies `NEEDS_EVIDENCE`, and `RetentionData.timeBasedGrading` defaults to `true`, so only hand-built data hits it.

### Doc and copy accuracy (minor)

- `threshold.md:261` claims every AUTO row with `dwell < 6` is rating 4 and `dwell >= 60` rating 2. Wrong twice: a wrong/guessed/skipped row is rating 1 at any dwell, and dwell 0 is rating 3. `threshold_analysis.md:519` has the correct predicate.
- `README.md:894` ("auto-graded Easy below this") is false at dwell 0; `README.md:2075` ("ignored until a cut is saved") is false — `GradingRulesStore.kt:136-154` adopts 6/60 on the first versioned read.
- `README.md`, `strings.xml:823` and `strings.xml:845` describe the suggestion as "about a tenth Easy / a tenth Hard", which the 2 s floor, 5 s minimum gap and 120 s ceiling can reshape (`Calibration.kt:310-320`).
- The shipped 6/60 is **not** the pair the shipped analysis recommends: `threshold_analysis.md` recommends 8 s Easy, keeps the bonus on the Hard end, never mentions 6 or 60, and its nearest row (8/30) yields 19.2 % Hard. `threshold.md`'s own 6/60 row (5.4 / 75.5) matches no analysis row. Nothing argues for making the Hard end absolute at 60. *(D8 — arguably major: the central tuning decision is unsupported by the evidence that ships with it.)*
- `threshold.md` §5 cites run `43473648330` as *the* verification, five behavioural commits and two red runs earlier; the tip is green (`43492867486`). Class counts also disagree: "163 classes" (`threshold.md` 4.6) vs "326 pure-JVM classes" (PR body). *(T8)*
- `threshold.md:1` instructs "delete it or fold it into `README.md` before the release PR is merged", while §4.8 marks "this file ships too" as done. 934 lines of planning docs are committed. *(T9)*

---

## Nits

- `BackupRestorer.restore` (non-streaming) and `BackupSource.collect` have no production caller — tests only *(I14)*.
- `StatsStore.kt:271` still explains a "session-end controller on `Dispatchers.IO`" that this branch deleted *(I11)*.
- `RepositorySettingsTest.kt:133-142` has two stacked KDoc blocks after the edit *(T13)*.
- `SpacedRepetitionTest` test name "the fast boundary is inclusive below and exclusive at" no longer matches the wording *(T13)*.
- `NoDeadCodeTest` is a name denylist, so it cannot catch new orphans (`Calibration.minimumDetectableEffect` is already unreferenced) *(T13)*.

---

## Behaviour → test map (new behaviour only)

| Behaviour | Test |
|---|---|
| Absolute cuts: 1–5 Easy, 6–59 Good, ≥60 Hard | ✅ `SpacedRepetitionTest` — pinned |
| Clock bands and band ≡ grade | ✅ `StudyDwellBandTest`, incl. three configs |
| `gradedDwellSeconds` half-up | ✅ `StudyDwellBandTest` (1 500 ms only) |
| ms → grade composition at 5.5 s / 59.5 s | ❌ none |
| Correctness-only pure function | ✅ `SpacedRepetitionTest` |
| Correctness-only through `StudyViewModel` → `review_log` | ❌ none |
| Neutral clock tint under correctness-only | ❌ none |
| Cut fields hidden while the switch is on | ❌ none |
| Cut pair survives toggling back | ✅ `SpacedRepetitionTest`, `RepositorySettingsTest` |
| Rule switch → `reason=rule`; cut edit → `manual` | ✅ `GradingRulesStoreTest` |
| No-op save, reset, crash repair, restore numbering, upgrade epoch | ✅ `GradingRulesStoreTest` |
| Fresh install seeds correctness-only | ⚠️ store-level only, not `McqApplication` |
| Migration 25→27 / 26→27 | ✅ `MigrationTest` (26→27 added) |
| Backup round-trip of `grading_versions` | ⚠️ `correctnessOnly=false` only |
| `correctnessOnly=true` round-trip | ❌ none |
| Pre-v27 backup with `ladderBlocks` imports | ❌ none |
| `suggestCuts` / `suggestCutsFromDwell` maths | ✅ `CalibrationTest` — strong, hand-checked |
| Suggestion reaches `RetentionData` and renders | ❌ none |
| Calibration card hidden under correctness-only | ❌ none |

---

## Verified strengths

- **Double-grading is handled.** `StudyViewModel.check()` (`:774`) flips `revealed` synchronously before returning; `next()`, `grade()` and `stop()` all claim the card first (`:1078` uses `compareAndSet`). A second tap short-circuits.
- **Rotation is safe.** The ViewModel survives and `questionElapsedMillis` is banked per segment, so no dwell is lost mid-card.
- **Backup envelope is compatible both ways.** `VERSION` stays 2, `ladderVersions` is kept as a wire key, `ladderBlocks` is skipped, missing `grading_rules_version` re-seeds 6/60, and wipe + import + replay share one transaction.
- **No dangling references** to `LadderController`/`LadderStore`/`LadderPolicy`/`ladder_versions` remain under `app/` (only the deliberately kept `ladderVersion` column and wire key).
- **Repo hygiene holds:** feature branch off `main`, draft PR #81, no `versionName`/`versionCode` or workflow change, no debug affordance, schema JSON exported with the new column.
- **Domain tests are the strongest part** of the change; the calibration suggestion arithmetic was re-derived by hand and matches production.

---

## Decisions needed (not defects)

1. **6/60 vs the analysis's 8/(30+b).** Who decided the Hard end becomes absolute, and why 60? By the docs' own numbers Hard becomes ~5 % of reviews. Is Hard meant to survive?
2. **Should the app suggest a *starting* pair from dwell quantiles at all**, or is that the relative rule creeping back? If kept, is a 10 %-Hard target defensible?
3. **Is a silent reset of a hand-tuned 8/30 acceptable?** Should the UI say the pair was retired rather than just showing 6/60?
4. **Should `dwell == 0` be Easy**, or is "under half a second is not a real answer" deliberate?
5. **Should the suggestion be applicable (prefill) rather than memorised?**
6. **Was v27 ever installed outside dev/CI phones?** If yes, 27→28 is mandatory.
7. **Is "Reset" allowed to leave correctness-only on?** The test currently asserts it turns off.
8. **Do the 934 lines of planning docs ship?** `threshold.md` says they must be folded or deleted first.
9. **Is a global cut suggestion from one paper's dwells acceptable** in a paper-scoped Statistics view, or must the card name its scope?

---

## Suggested order of work

1. **B1–B4** (readers seed the version; freshness from the DB, not the file or the empty DataStore; Reset leaves the rule alone) — these decide whether the feature works at all.
2. **M1, M2, M4, M5** — the user-visible story: correct the upgrade claim, keep the forecast chart, make the suggested pair reachable, report the real gate.
3. **M6, M7, M9, M10** — the tests that would have caught 1–4; plus `correctnessOnly=true` and legacy-backup fixtures.
4. **M8** — one build to regenerate `27.json`; decide 27→28 only if v27 escaped dev/CI.
5. **M3 + docs (6/60 rationale, README/`threshold.md` predicates, stale CI citation)** before un-drafting.
6. Fold or delete `threshold.md`/`threshold_analysis.md` per their own instruction, or get an explicit owner decision to ship them.

---

## Round status (appended 2026-11-14)

The findings above are the evidence record; this table is the outcome. B1–B4 were fixed over four rounds of subagent implementation, each round followed by a three-specialist review sweep (design/domain, implementation/integration, tests/docs). The final verification pass was run by the supervisor, not a subagent, because the subagent quota was exhausted.

| Finding | Outcome | Commit | Proof |
|---|---|---|---|
| **B1** epoch can start at 0 | Fixed | `58c5d8e` | `StatsRetentionStreamTest.openingStatisticsOnAMigratedV27InstallSeedsANewEpoch`; `GradingRulesStoreTest.schedulerConfigNowCarriesTheIdStoredWithTheSeededCuts`; `StatsSeedFailureTest` (a failed seed surfaces an error, never a version-0 report) |
| **B2** fresh-install file probe | Fixed | `58c5d8e` | `StartupTest.aNewInstallSeedsCorrectnessOnlyFromTheBackgroundProbe`, `anInstallWithHistoryIsNotSeeded` (real `Application`); `GradingRulesStoreTest.onePaperMakesTheInstallAnUpgrade`, `aReviewRowAlsoMakesTheInstallAnUpgrade` |
| **B3** seed voided by any preference | Fixed | `58c5d8e` | `GradingRulesStoreTest.anUnrelatedPreferenceAndAnEmptyDatabaseStillSeedCorrectnessOnly`, `anExplicitlyStoredFalseIsNeverOverwrittenByTheSeed` |
| **B4** Reset flips the rule | Fixed | `58c5d8e` | `SettingsViewModelResetTest.resetKeepsTheRuleInTheUiAndInTheStore`; `GradingRulesStoreTest.aResetUnderCorrectnessOnlyKeepsTheRuleAndRecordsIt`, `aResetWithAnUnchangedPairLeavesTheVersionAndEpochAlone`, `aFirstResetOfARetiredPairMintsNoManualRow` |
| **M1** upgrade retires a tuned pair | Partly — now documented, not preserved | `d4ed972` | README 2104 states the retirement; no in-app notice yet, and the old value is still not kept |
| **M2** calibration chart hidden under correctness-only | Open | — | — |
| **M3** suggestion is a relative rule | Open (owner decision) | — | — |
| **M4** suggested pair is unusable | Open | — | — |
| **M5** "not yet" line states the wrong gate | Open | — | — |
| **M6** no end-to-end correctness-only test | Open | — | — |
| **M7** stats test excluded the new fields | Open | — | — |
| **M8** v27 amended in place | Open | — | `27.json` still hand-edited; needs one build to regenerate |
| **M9** backup coverage | Partly | `58c5d8e` | marker exclusion and restore-kept-rule are covered; `correctnessOnly=true` in a version row and legacy `ladderBlocks` fixtures are not |
| **M10** shared DataStore leakage | Partly | `58c5d8e` | `robolectric.properties` + `TestMcqApplication` + `SettingsResetRule` replace the probe race; the general leak across unrelated classes remains |

Residual risk now documented in the code and README: if the first-start probe fails and the learner imports a paper before a retry succeeds, the install is treated as an upgrade for good and the correctness-only default is lost silently.

Local evidence for `58c5d8e`: `compileDebugKotlin`, `compileDebugUnitTestKotlin`, `compileDebugAndroidTestKotlin`, `lintDebug` (no new issue in a touched file) and the 165-class pure-JVM suite (1478 tests) all pass. Every Robolectric class is CI-only on this aarch64 host — including all the new oracles above — so the branch is unproven until CI runs.

---

*Findings marked "verified" were re-checked against the tree during synthesis; the rest carry the reviewing agent's `path:line` evidence and should be re-confirmed when fixed.*
