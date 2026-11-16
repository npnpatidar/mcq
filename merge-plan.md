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

Still to prove: the Robolectric half of run `43786336543` (`BackupSchedulingTest`
version/alias assertions, `ExposureInStudyDayTest`, the `.apkg` chain after the
retention parameter was deleted). Locally, 86 pure-JVM tests pass and both source
sets compile.

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
# CI green on 5b0a52a, then:
gh pr ready 82                       # un-draft (owner's word)
gh pr merge 82 --merge               # merge commit; release notes auto-link PRs
git switch main && git pull
```

Do **not** release yet if both are going out together; see Step 4.

### Step 2 — rebase #81 onto the merged main and renumber to 28

```bash
git fetch origin
git switch feature/absolute-dwell-thresholds
git rebase origin/main               # resolve the 8 conflicts per §3
```

Then the schema work, in order:

1. `AppDatabase.kt`: bump `version = 28`; keep `MIGRATION_26_27` exactly as
   `main` now has it (FSRS-7's), rename its own migration to
   `MIGRATION_27_28` and add it to the builder list.
2. `Entities.kt`: no change needed unless the rebase duplicated `grading_versions`.
3. Regenerate the schema export: build with KSP schema export enabled (the repo
   already writes `app/schemas/…/N.json` on compile), so `28.json` is produced
   from the merged entities. Keep `27.json` byte-identical to `main`'s — never
   hand-edit either JSON.
4. `MigrationTest.kt`: merge both test bodies; assert `26 → 27` (fast trace) and
   `27 → 28` (`grading_versions`), and that a fresh install at 28 validates.
5. `RepositorySettings.kt`: both blocks live side by side — `ensureFsrs7` must
   stay ahead of the grading-rules defaulting, and neither key may shadow the
   other's version counter.

### Step 3 — conflict-by-conflict intent

- **`AGENTS.md`**: keep one Supervisor-workflow block (they are near-duplicates;
  `fsrs-7`'s is the superset — it carries the upstream-references and
  quota-retry rules). Keep both Scheduling and grading sections.
- **`McqApplication.kt`**: one `applicationScope`, one exception handler — take
  `absolute`'s handler (it is the backstop) with `fsrs-7`'s comments and call
  sites. The FSRS-7 migration, the rebuild and the grading-rules seeding must all
  use that single scope and stay idempotent.
- **`McqRepository.kt`**: keep both entry points; the grading restore and the
  FSRS-7 rebuild are independent, and the rebuild must not run inside the
  grading path's transaction.
- **`RepositorySettings.kt`**: both key families, both one-shot notices, and the
  two version counters (`fsrs_fit_version`, `grading_rules_version`) must not be
  conflated.
- **`StudySchedulerSection.kt`**: both control groups; grading cuts above the
  FSRS-7 Optimize block, per `README.md` §3.
- **`MigrationTest.kt` / `RepositorySettingsTest.kt`**: union of both tests; no
  assertion may be dropped to make the merge pass.

### Step 4 — release

Version is derived from the tag (`assembleRelease` maps `0.0.N` → `versionCode`),
and the release workflow fires on any `*.*.*` tag: it builds, signs, publishes
`mcq-<tag>-signed.apk` plus `.sha256`, with auto-generated notes.

- **Both out together**: merge #82, rebase/renumber/merge #81, then tag the second
  merge commit `0.0.52` once.
- **#82 alone first**: tag `0.0.52` after Step 1, then `0.0.53` after Step 3.

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

## 7. Open questions for the owner

1. **Order** — confirm #82 then #81 (recommended), or the reverse.
2. **One release or two** — `0.0.52` covering both, or `0.0.52` for FSRS-7 and
   `0.0.53` for grading.
3. **Merge strategy** — merge commit (recommended; the tag then names the merge
   commit, which is what the release workflow expects) or squash.
4. **Un-draft timing** — now, or after the renumbering lands on #81.
5. **Pre-merge fixes** — whether the remaining audit minors (notably m2: a
   degenerate fit could overwrite a real fit, and m6: a restore misreported as
   failed) ship after the merge or are worth a small follow-up PR first.
