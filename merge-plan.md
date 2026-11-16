# Merge plan — PR #82 (`fsrs-7`) and PR #81 (`absolute-dwell-thresholds`)

Prepared 2026-11-16. Snapshot of the two open PRs and exactly what must happen
before either can be merged. No branch in this repo was modified to produce it;
the merge-conflict list below comes from `git merge-tree` against the real commits.

## 1. Snapshot

| | PR #82 `feature/fsrs-7` | PR #81 `feature/absolute-dwell-thresholds` |
|---|---|---|
| Title | feat(scheduler): upgrade the FSRS engine to FSRS-7 | feat(grading): grade on absolute 6 s / 60 s dwell cuts |
| Draft | yes | yes |
| Base | `main` @ `7f47596` | `main` @ `7f47596` |
| Ahead / behind | 24 ahead, 0 behind | 23 ahead, 0 behind |
| GitHub mergeable into `main` | MERGEABLE (UNSTABLE = checks running) | MERGEABLE (CLEAN) |
| Latest CI | run `43786336543` on `5b0a52a`, in progress | run `43723808510` on `ef2ffa5`, success |
| Files changed | 62 | 79 |
| Room version it claims | **27** | **27** |

Both branches are individually mergeable into `main`. **They are not mergeable in
sequence as they stand** — see §3.

## 2. What is already done for #82

The audit (`fsrs_audit.md`) is committed, its five rulings are implemented, and
the four commits are pushed:

`e997185` import retention · `d3f4063` export format v2 · `c8a9816` exposure
test · `5b0a52a` docs.

Two further pre-merge fixes are in flight on the same branch by owner ruling
(§7): **m2** (a degenerate fit must not overwrite a fitted parameter set) and
**m6** (a rebuild failure must not be reported as a failed restore). They land
before the merge, so the audit's "still open" list shrinks by those two. Both
were reviewed; the review's conditions are being applied, including one real
defect the m2 fix itself created: the Optimize screen still recomputed the
loss comparison, so it announced a fit the tuner had just refused. It now
reports the tuner's own `stored` flag.

m6's reviewer also corrected the audit's stated symptom: `BackupRestorer`
already caught `Exception` around the settings write, so a rebuild failure
produced a **false partial note blaming the settings**, not a "Restore failed"
banner. The fix stands on its own merit; the audit row now says so.

**`5b0a52a` is proven green.** CI run `43786336543` passed all four jobs —
2206 tests, 0 failures, 0 errors, 7 skipped over 267 suites — including every
class only x86_64 can run: `ExposureInStudyDayTest`, `BackupSchedulingTest` (10),
`StreamingImporterEquivalenceTest` (14), `AnkiPackageReaderTest` (38),
`AnkiStreamEquivalenceTest` (3), `NoDeadCodeTest` (12). Both APKs from that run
are in `/sdcard/Download`. The evidence is recorded in `fsrs_audit.md` §10.

The m2/m6 commits will supersede that head, so they need one final green run of
their own before the PR is un-drafted.


## 3. Two blockers

### B1 — both branches ship Room schema version 27, with different schemas

| Branch | `27` contains |
|---|---|
| `fsrs-7` | `card_state.stabilityFast`, `review_log.stabilityFastAfter` |
| `absolute-dwell-thresholds` | new table `grading_versions` (`id`, `easySeconds`, `slowSeconds`, `correctnessOnly`, `effectiveFrom`, `reason`) |

Each defines its own `MIGRATION_26_27` and its own
`app/schemas/com.mcqapp.data.local.AppDatabase/27.json`. Merging both is not a
text conflict to patch: **the second branch must become version 28**, keep the
first branch's `26→27`, add `27→28`, and ship a regenerated `28.json`. Nothing is
released yet (`main` is on 26; latest tag `0.0.51`), so renumbering is legal
today and will not be tomorrow once a 27 ships to devices.

### B2 — eight files conflict when the two are merged directly

`git merge-tree --write-tree feature/fsrs-7 feature/absolute-dwell-thresholds`
reports these, with the reason:

| File | Why |
|---|---|
| `AGENTS.md` | both add a "Supervisor workflow" block at the same anchor |
| `AppDatabase/27.json` | add/add, two different schemas (B1) |
| `McqApplication.kt` | both add startup coroutine plumbing (`fsrs-7`: `applicationScope` for migration + rebuild; `absolute`: `open class` + `CoroutineExceptionHandler` + its own scope) |
| `data/repository/McqRepository.kt` | both add restore/upgrade entry points |
| `data/repository/RepositorySettings.kt` | `fsrs-7`: `FSRS_MODEL_VERSION`/`ensureFsrs7`/`fsrs7Notice`; `absolute`: `easySeconds`/`slowSeconds`/`gradingVersion` + `GRADING_RULES_VERSION` |
| `ui/settings/StudySchedulerSection.kt` | both add controls to the same section |
| `test/MigrationTest.kt` | both assert a 26→27 migration |
| `test/RepositorySettingsTest.kt` | both add upgrade/key tests |

Everything else auto-merges, including `Backup.kt`, `Daos.kt`, `Entities.kt`,
`StudyStore.kt`, `SpacedRepetition.kt`, `StudyViewModel.kt`, `strings.xml`,
`MigrationTest`'s neighbours, `SpacedRepetitionTest`, `RepositoryTest`.

## 4. Recommended order: #82 first, then #81

1. **`fsrs-7` keeps 27.** It is the larger, audited change; landing it while the
   audit is fresh is worth more than the smaller branch's lower risk. Its
   migration is also the one already covered by a migration test in CI.
2. **`absolute-dwell-thresholds` rebases onto the merged `main` and becomes 28.**
   Renumbering once, on the branch with *one* new table, is safer than doing it
   to `fsrs-7`'s 62-file change afterwards.

If you prefer the reverse, everything in §5 still applies with the two branches
swapped — but then `fsrs-7` pays the renumbering cost, and its `27.json` must be
regenerated, which is the larger surface.

## 5. Runbook

### Step 1 — finish #82, merge it

```bash
# CI green on the final head (the m2/m6 commits), then:
gh pr ready 82                       # un-draft (owner's word)
gh pr merge 82 --merge               # merge commit; release notes auto-link PRs
git switch main && git pull
```

Do **not** tag here — the single `0.0.52` goes on the second merge commit (§7).

### Step 2 — bring #81 up to date and renumber to 28

```bash
git fetch origin
git switch feature/absolute-dwell-thresholds
git merge origin/main                  # resolve the conflicts per Step 3, once
```

**Merge, not rebase.** The branch carries 23 commits, several of which touch the
same conflicting files (`AppDatabase.kt`, `RepositorySettings.kt`, the two
tests). A rebase replays them one at a time, so the same conflict is resolved
repeatedly and an early mistake is only visible at the end. Merging `main` in
resolves everything in a single pass, keeps the branch's history intact, and
still lands on `main` as a merge commit — which is the ruling either way.

Then the schema work, in order:

1. `AppDatabase.kt`: bump `version = 28`; keep `MIGRATION_26_27` exactly as
   `main` now has it (FSRS-7's two `ALTER TABLE`s), rename its own migration to
   `MIGRATION_27_28` and add it to the builder list. The body is unchanged apart
   from the version numbers: create `grading_versions`, `INSERT … SELECT` from
   `ladder_versions` (dropping `previousId`, `blockId`, `policyVersion`),
   `DROP TABLE ladder_blocks`, `DROP TABLE ladder_versions`.
2. `Entities.kt`: no change needed unless the rebase duplicated `grading_versions`.
   The ladder entities stay deleted, since `27→28` drops their tables.
3. Regenerate the schema export: build with KSP schema export enabled (the repo
   already writes `app/schemas/…/N.json` on compile), so `28.json` is produced
   from the merged entities. Keep `27.json` byte-identical to `main`'s — never
   hand-edit either JSON. Expect `28.json` = `fsrs-7`'s `27.json` **plus**
   `grading_versions` **minus** the two ladder tables.
4. `MigrationTest.kt`: merge both test bodies; assert `26 → 27` (fast trace only,
   `main`'s schema) and `27 → 28` (`grading_versions` populated, ladder tables
   gone), keep the ladder test on the full `25 → 28` chain, add one `26 → 28`
   test whose v26 fixture carries both an FSRS-6 card state and ladder rows, and
   assert a fresh install at 28 validates. The `26→28` test is the one that
   catches the destructive step being wrong.
5. `RepositorySettings.kt`: both blocks live side by side — `ensureFsrs7` must
   stay ahead of the grading-rules defaulting, and neither key may shadow the
   other's version counter. Take `absolute`'s `(context, installFreshness)`
   constructor and graft `fsrs-7`'s members into it; grep `SettingsStore(` for
   call sites the conflict markers do not show.

### Step 3 — conflict-by-conflict intent

What each side actually does, read off the branch tips, so the resolution is a
mechanical decision rather than a judgement call under conflict markers.

| File | `fsrs-7` brings | `absolute` brings | Resolution |
|---|---|---|---|
| `AGENTS.md` | the superset Supervisor-workflow block (upstream-references, quota-retry rules) plus the whole FSRS-7 Scheduling/Anki/JSON sections | its own near-duplicate Supervisor block, plus grading and ladder sections | keep `fsrs-7`'s workflow block, keep both feature sections, keep `absolute`'s grading text |
| `27.json` | `card_state.stabilityFast`, `review_log.stabilityFastAfter` | add `grading_versions`, **drop `ladder_versions` and `ladder_blocks`** | not a text merge: keep `fsrs-7`'s file as `27.json` byte-identical, generate `28.json` (§2) |
| `McqApplication.kt` | `applicationScope` launching `ensureFsrs7()` then `rebuildFsrs7Memory()`, each in its own `try` | `open class`, `startupExceptionHandler`, `startupScope`, `seedsInstallFreshnessAtStartup` opt-out, and a startup job that decides the first-run grading rule | one scope = `startupScope` (handler included), three launched jobs: FSRS-7 migration, FSRS-7 rebuild, grading-rule seed. Keep `absolute`'s `open class` and opt-out flag — its tests subclass it |
| `McqRepository.kt` | `ensureFsrs7`, `rebuildFsrs7Memory`, `fsrs7Notice`/`dismissFsrs7Notice`, and `settingsRestore` calling the rebuild | grading-rule restore/seed entry points, `installFreshness` probe wiring | keep both entry points. `settingsRestore` keeps the **m6** shape (rebuild off the restore's failure path); the grading restore keeps its own transaction. Neither runs inside the other's |
| `RepositorySettings.kt` | `FSRS_MODEL_VERSION`, `ensureFsrs7`, `fsrs7Notice`, `fsrs_fit_version` provenance, rebuild cursor/done flags | **changes `SettingsStore`'s constructor** to `(context, installFreshness)` plus a `@VisibleForTesting (context, freshInstall)`, adds `GradingGuard`, `correctnessOnlyKey`, `writeCuts`, grading-rules versioning | take `absolute`'s constructor shape (every caller follows it) and graft `fsrs-7`'s members into the body. Both key families and both one-shot notices survive; `fsrs_fit_version` and `grading_rules_version` stay separate counters |
| `StudySchedulerSection.kt` | Optimize block, FSRS-7 notice, response-time footnote additions | `config: SchedulerConfig?` null-guard that renders a spinner until the first config arrives, plus `onFastChange`/`onSlowChange`/`onRuleChange` and the correctness-only switch with its conditional cut rows | take the null-guard and the extra callbacks wholesale (all call sites come from `absolute`), then re-add `fsrs-7`'s block inside `AnkiSchedulerBody`. Grading controls above the Optimize block, per `README.md` §3 |
| `MigrationTest.kt` | `MIGRATION_26_27` in the helper list, `createV26WithSeed`, `migration 26 to 27 adds the fast stability trace` | `MIGRATION_26_27` in the helper list, `createV26WithLadderHistory`, a `25→27` test asserting `gradingVersion == 0` and the `grading_versions` cascade | union. Helper list becomes `…25_26, 26_27, 27_28`. Keep `fsrs-7`'s `26→27` test as the checkpoint, retarget the ladder test to `25→27→28`, and add one end-to-end `26→28` test from a v26 file carrying **both** a FSRS-6 card state and ladder rows |
| `RepositorySettingsTest.kt` | the FSRS-7 bookkeeping keys removed in both reset helpers, plus `ensureFsrs7…` tests | `SettingsResetRule`, the stale-cut-pair test, correctness-only round trip | union both reset helpers (the FSRS-7 keys must be cleared **and** the reset rule applied), keep every test from both sides |

Two things make this meatier than a normal rebase, and are the reason the
renumbering is safer on this branch than on `fsrs-7`:

1. **`absolute`'s `26→27` is destructive.** It creates `grading_versions`, copies
   `ladder_versions` into it, then `DROP TABLE`s `ladder_blocks` and
   `ladder_versions` — four of the six new columns (`previousId`, `blockId`,
   `policyVersion`) are deliberately not carried over. After `fsrs-7` lands,
   that same body has to run as `27→28`, and the merged `27.json` (which still
   **has** the ladder tables) must stay byte-identical to `main`'s.
2. **`absolute` changes a constructor every caller shares.** `SettingsStore`'s
   `(context)` becomes `(context, installFreshness)`. Any `fsrs-7` call site that
   reconstructs the store has to follow; grep for `SettingsStore(` rather than
   trusting the conflict hunks.

### Step 3b — a "clean" merge that does not compile

`git merge-tree` reports `AppDatabase.kt` as **auto-merged, no conflict**, and
that result is broken: both sides add a member called `MIGRATION_26_27`, and the
merged file contains **two** definitions in the same object (at the merge-tree of
the two tips, lines 863 and 972) plus the builder list referencing the name once.
Kotlin rejects duplicate declarations, so the branch would fail to compile with
no conflict marker anywhere to explain it.

So the renumbering is not optional cleanup after a successful merge — it is the
fix for a merge that silently doesn't build. Treat it as a conflict: the merged
`MIGRATION_26_27` must be `fsrs-7`'s body only, `absolute`'s body renamed
`MIGRATION_27_28`, and the builder list `… 25_26, 26_27, 27_28`.

Cheap guards after the rebase, before any build:

```bash
grep -c 'val MIGRATION_26_27' app/src/main/java/com/mcqapp/data/local/AppDatabase.kt   # must be 1
grep -n 'MIGRATION_27_28' app/src/main/java/com/mcqapp/data/local/AppDatabase.kt        # definition + list entry
grep -n 'version = ' app/src/main/java/com/mcqapp/data/local/AppDatabase.kt            # must be 28
git diff origin/main -- app/schemas/.../27.json                                        # must be empty
```

A duplicate-declaration scan of the other auto-merged files (`Daos.kt`,
`ChunkedQueries.kt`, `Backup.kt`, `StatsStore.kt`, `SpacedRepetition.kt`,
`strings.xml`) found no second instance of this class of damage; overlapping DAO
method names are distinct interfaces, not duplicate members. The compile is still
the authority.

### Step 4 — release

Version is derived from the tag (`assembleRelease` maps `0.0.N` → `versionCode`),
and the release workflow fires on any `*.*.*` tag: it builds, signs, publishes
`mcq-<tag>-signed.apk` plus `.sha256`, with auto-generated notes.

- **One release (ruled)**: merge #82, rebase/renumber/merge #81, then tag the
  second merge commit `0.0.52` once. Nothing is tagged after Step 1.

```bash
git tag 0.0.52 <merge-commit> && git push origin 0.0.52
gh run list --workflow=release.yml --limit 1
gh release view 0.0.52          # assets: mcq-0.0.52-signed.apk + .sha256
```

### Step 5 — verification gate before either merge

1. CI green on the PR head (both jobs that matter: `build`, `ui-test`).
2. `:app:compileDebugKotlin` + `:app:testDebugUnitTest` green locally for the
   pure-JVM half; Robolectric is CI-only on this host.
3. `MigrationTest` covers `26→27→28`, plus a fresh install at the merged version.
4. APKs downloaded to `/sdcard/Download` (`app-debug.apk`, `app-release.apk`).
5. Smoke list on device, in this order: fresh install → study a card → grade by
   dwell cuts (grading) → confirm the FSRS-7 schedule and due date → card info
   sheet shows both traces and no `d` suffix on stability → export JSON (check
   `"version":2`, both keys) → import into the *other* installed build (debug
   beside release) → Anki `.apkg` round trip → backup/restore.

## 6. PR hygiene still to do

- **#82 description** predates the audit. It should summarise: FSRS-7 engine
  (upstream-exact constants, parity vector from `ts-fsrs`), Room 26→27,
  export format v2, import retention ruling, the `cards.data` limitation, and a
  pointer to `fsrs_audit.md` with the remaining open minors (m2–m6, m8–m10,
  m12, m13, m15).
- **#81 description** should state its Room 27 → 28 renumbering once it rebases,
  and that it lands after #82.
- Both are drafts: flip to ready only when you say so.
- No review approvals are recorded on either PR (`reviewDecision` empty) — if
  branch protection requires one, that is a click only you can make.

## 7. Owner rulings (2026-11-16)

| # | Question | Ruling |
|---|---|---|
| 1 | Order | **As recommended**: #82 first keeping 27, then #81 merged (not rebased) and renumbered to 28 |
| 2 | Releases | **One** release, `0.0.52`, tagged on the second merge commit |
| 3 | Merge strategy | **Merge commits** — the tag then names the merge commit, which is what the release workflow expects |
| 4 | Un-draft timing | **As recommended**: #82 to ready as soon as its final CI is green; #81 stays draft until its renumbering lands and its CI is green |
| 5 | Pre-merge fixes | **Fix m2 and m6 on this branch before it merges** — a degenerate fit must not overwrite a fitted set, and a rebuild failure must not be reported as a failed restore |
| 6 | Restore vs rebuild | **Background rebuild (A).** `Restored` is reported as soon as the data and settings are in; the FSRS-7 rebuild continues on the process scope. The gap where a card the rebuild has not reached schedules from `Sf = S` — and a card reviewed inside that gap keeps its review-derived value — is accepted, recorded as limitation 8 in `fsrs_audit.md` and as a rule in `AGENTS.md` |

Consequences for the runbook above: Step 1 merges #82 and does **not** tag;
Step 2 merges `main` into #81, renumbers it and merges it; Step 4 then tags
**`0.0.52`** on that second merge commit and verifies its assets — there is no
`0.0.53`.

## 8. What actually happened (2026-11-16)

| Step | Result |
|---|---|
| #82 | Merged as `4a816fb`; head `b8751b1`, CI run `43794588314`: 2216 tests, 0 failures, 0 errors, 7 skipped across 269 suites; all four jobs green. APKs downloaded. |
| #81 up to date | `git merge origin/main` — single merge commit `7b8f7f4`, **29 commits ahead of the old remote tip `ef2ffa5`** (the common base with `main` is `7f47596`). No rebase: the same conflicts would have replayed across 23 commits. |
| Renumber | `version = 28`; `MIGRATION_26_27` is `main`'s body only; this branch's old body became `MIGRATION_27_28`; `27.json` byte-identical to `main`'s; `28.json` generated (adds `grading_versions`, drops the two ladder tables). |
| Three reviews | Schema/design, implementation/integration, tests/docs — all reported, no blocker in the merged code itself. The one blocker is "no CI has ever built this tree", which the push resolves. |
| Fix pass | `069eb76` — the migration made total, two new `MigrationTest` fixtures, the startup-job opt-out, the DataStore leak closed, 15 version markers retargeted. **30 commits ahead of `ef2ffa5`.** Three reviewers re-checked it: no blocker, major, or runtime defect; the remaining findings were prose and were applied in two follow-up commits — `a59fc05` (source and test prose) and `34b51ec` (one assertion-message string) — with the supervisor's own corrections to these two documents still uncommitted at the time of writing. |
| CI on the merged tree | **Pending** — the branch has not been pushed since the merge. Nothing in the merged tree has run on x86_64 yet. |

**Decisions taken on the review findings** (supervisor, reported to the owner):

1. **`MIGRATION_27_28` is made total.** Before `069eb76`, a pre-renumber branch v27
   (no ladder tables, no fast-trace columns) threw `no such table:
   ladder_versions` and then failed Room's schema check — a per-launch crash for
   unreleased dev installs only. Reviewer 1 said only documentation could help;
   reviewer 2 proved a guard works. Both are done: a Kotlin-side `sqlite_master`
   check before the copy and `PRAGMA table_info` checks before each `ALTER`, plus
   the caveat in the PR body and the release note. Verified fact worth
   keeping: a SQL `WHERE EXISTS` guard does **not** work, because SQLite resolves
   `FROM ladder_versions` at prepare time.
2. **`reason` is documented, not normalised.** Migrated **and restored** rows keep
   v26's `proposal`/`automatic`/`rollback`; the enum stays write-side only.
   Rewriting history in a migration was rejected by two reviewers.
3. **Grading prose is reworded behaviour-first, not by version number.** Feature
   introduction markers become `(v28)`; FSRS-7's stay at v27; and the residual
   "pre-v27" wording becomes "from before the absolute grading rule" / "rows
   graded under the retired reading-allowance rule" / "a backup without
   `grading_rules_version`". A mechanical `pre-v27` → `pre-v28` substitution was
   explicitly rejected: branch-v27 data already used the absolute rule, so the
   number never denoted one thing.
4. **`TestMcqApplication` opts out of the FSRS-7 startup jobs too**, so
   `StartupTest`'s file-absence assertion is an invariant again rather than a
   race with `ensureFsrs7`/`rebuildFsrs7Memory`.
5. **Accepted, not fixed**: no CI schema-drift check; `SignalQueryTest`'s
   parallel `SettingsStore` oracle; the `Fsrs7UpgradeTest` freshness coupling.
   All three are nits with no behavioural consequence today.


