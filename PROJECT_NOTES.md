# Project notes — MCQ app

App-specific facts for agents working on this repository. The portable process
and tooling rules live in `AGENTS.md`; read that first, then this file. Anything
here is either an owner rule for this product or a fact about this codebase
that is easy to get wrong.

- Repo: `git@github.com:npnpatidar/mcq.git`, branch `main`.
- Stack: Kotlin, Jetpack Compose (Material 3), Room, Navigation-Compose,
  DataStore, Coil, kotlinx.serialization. Single Gradle module `:app`
  (`rootProject.name = "MCQApp"`).
- Application id `com.mcqapp`; the debug variant adds `.debug` and therefore
  installs beside the release. Namespace and Room database class stay
  `com.mcqapp`.
- README documents the app for users; `BUILDING.md` documents a local ARM64
  build path. Neither is a working plan — keep plans out of the repository.

## Product premises (owner rule, set 2026-11-15)

Judge behaviour, find bugs, review and recommend under these premises. A finding
that only exists because one of them is false is not a defect and must not drive
code or copy changes.

- **The user is honest.** Nobody is gaming the app, farming Easy grades, or looking for a
  loophole. Do not design, test or review for adversarial use.
- **Normal-speed learners.** This is an everyday study app, not a speed test. A pace under
  one or two seconds per card is rare and not a population to design for; a rule whose only
  benefit goes to a sub-second speed-runner is not worth its complexity.
- **No clock games.** Do not assume a learner stops the clock, reads the question, then
  restarts it before answering. The pause control exists for interruptions. A scenario built
  on pausing to think *around* the timer is out of scope, so evidence stored as `0 s` should
  be read as a genuinely quick answer, not as a paused one.

## Room schema (version 29)

- The database is at **Room version 29** (`app/src/main/java/com/mcqapp/data/local/AppDatabase.kt`).
  Exported schemas live in `app/schemas/com.mcqapp.data.local.AppDatabase/`, one JSON per
  version; `29.json` is the current one.
- The live upgrade chain at the tail is **25 → 26 → 27 → 28 → 29**:
  - `MIGRATION_25_26` creates the adaptive ladder (`ladder_versions`, `ladder_blocks`).
  - `MIGRATION_26_27` is FSRS-7: it adds `card_state.stabilityFast`
    (`REAL NOT NULL DEFAULT 0.0`) and `review_log.stabilityFastAfter` (nullable).
  - `MIGRATION_27_28` replaces the ladder with the fixed grading rule
    (`grading_versions`) and drops both ladder tables.
  - `MIGRATION_28_29` adds the back-search columns (`questions.backText`,
    `backTextFolded`, default `''`) and fills them from each explanation, then converts
    every stored one-option question into a flashcard (answer into the back, option and key
    deleted, both `questions.contentHash` and every `card_state.contentHash` rewritten in
    the same transaction so the schedule survives). A flashcard is a zero-option question;
    no type column exists.
- **Two incompatible shapes are both called 27**, because the two parents of the
  merge that renumbered Room to 28 each used that number. A `main` v27 has
  `ladder_versions` and already has the fast-trace columns; a v27 written by the
  pre-merge branch (head `ef2ffa5`) had already copied the ladder into
  `grading_versions`, dropped both ladder tables, and never had the fast traces.
  `MIGRATION_27_28` handles both: the copy runs only when `ladder_versions`
  exists (checked in Kotlin through `sqlite_master`), and each fast-trace column
  is added only when missing (checked with `PRAGMA table_info`). A SQL
  `WHERE EXISTS` guard cannot do this job — SQLite resolves the
  `FROM ladder_versions` reference when the statement is prepared, before any
  filter is evaluated.
- **What 27 → 28 discards:** `ladder_blocks` in full — the pair and card counters,
  the deltas, `outcome` and `decidedAt` behind a v26 automatic or proposal cut
  change have no successor. A version row keeps its `id`, the cut pair,
  `effectiveFrom` and `reason`; the `previousId` chain is discarded. `id`
  ordering reconstructs that chain only for ordinary edits, not after a
  rollback: the released v26 `LadderStore.rollback()` gave the rollback row the
  restored version's predecessor, so that row's parent skips the row it undid.
  `policyVersion` is not reconstructable at all.
- **`reason` is opaque history.** The live grading-rule writer emits only
  `prior`, `manual` or `rule`. Rows migrated or restored from an older build may
  carry `proposal`, `automatic` or `rollback`. Nothing may parse a stored
  `reason` against the current write vocabulary, and a migration must not
  normalise it.
- `review_log` is append-only and is not rebuilt. Its column keeps the name
  **`ladderVersion`** while the entity property is **`gradingVersion`** — the
  column name was deliberately left alone so the log never had to be rebuilt.
  The value now names a `grading_versions.id`; `0` means "written before the
  cuts were versioned".
- Room validates the schema after a migration chain completes, so a missing
  column or a wrong final schema fails the open rather than passing silently.

## Cross-paper moves (owner decision, 2026-11-17)

- A move to another paper **carries progress**: `card_state`, `review_log` and
  `question_stats` rows are re-keyed to the destination paper in the move
  transaction. The schedule (including due date and FSRS memory) and the review
  history survive; the destination paper's statistics include those reviews,
  which the owner explicitly accepted. A same-paper move changes only the
  category and leaves all progress rows alone.
- A question in a passage never moves alone: selecting one member re-homes the
  passage row and moves every member. Browse's dialog and completion toast use
  that passage-expanded count.
- On the rare collision where the destination paper already has a `card_state`
  row for that question, the **destination card and its own history win**, and
  the source paper's review history is **dropped, not merged**. `question_stats`
  keeps the destination counters but points at the destination paper/category.
- `review_log` content stays immutable; `paperId` is the single column a move
  may change (it is re-keyed for ordinary moves, deleted for collided sources).
- `StatsStore`'s calibration cache must be invalidated after rows are re-keyed
  or dropped: its key contains `scopePaperId` but no input moves when only
  `review_log.paperId` changes. The move returns a count of changed rows for
  that purpose.
- Cross-paper changes are confirmed in Browse's move dialog and in
  Library → Passages → edit (change Category). The editor's passage picker is
  same-paper only; it re-verifies cached passages and ignores a stale
  cross-paper pick rather than writing it silently.
- No backup/export format bump: scheduling keys already use the current
  `(paperId, questionId)`.

## Scheduling (FSRS-7)

- The study day starts at **04:00 local time** and is computed from calendar dates
  (`DayBoundary.studyDayIndex` / `boundaryOf`), not by adding fixed 24-hour ticks.
  This keeps due dates, exposure counts, Session day buckets and Anki day numbers
  correct across daylight-saving changes. Elapsed time in the FSRS formula stays
  fractional; only the resulting interval is whole days.
- The engine is FSRS-7: **34 weights**, two stability traces (`stability` slow,
  `stabilityFast` fast) and a difficulty-modulated *mixture* forgetting curve.
  It is a published upstream model, not ours: the constants and the parity vector
  track `open-spaced-repetition/fsrs-rs` tag `v7.0.0` (`model_v7.rs`,
  `parameter_initialization_fsrs7.rs`, `parameter_clipper_v7.rs`) and
  `ts-fsrs` `packages/fsrs/src/models/fsrs-7/`. Both are pinned in
  `FsrsSchedulerTest`; verify any change against those sources, not against an
  in-repo plan.
- **`stabilityFast == 0` means "unknown", not "zero".** Imported cards and rows
  the rebuild has not reached carry it; `FsrsScheduler.fastStabilityOf` collapses
  those onto `stability`. Never write a fabricated `Sf` — with three legitimate
  exceptions: a first exposure, an `Again`, and the Anki SM-2 import bridge,
  which mirrors upstream's `memory_state_from_sm2_scalar` (`Sf = 0.8·S`,
  `D = 5`, ease ignored). Those three are upstream behaviour; do not "fix" them.
- **Stability is not an interval.** `R(t = S)` is not the target retention, so
  every due date comes from `intervalForRetention`'s numerical solve. Do not
  render `S` with a `d` suffix, and do not set `S = interval` on import (the
  Anki path bisects the mixed curve instead).
- **No short-term branch.** Elapsed time stays fractional on purpose; rounding it
  to whole days changes the model. Only the resulting interval is whole days.
- **A failed card relearns in the sitting, but only three times a study day.**
  `FsrsScheduler.MAX_EXPOSURES_PER_STUDY_DAY` bounds the loop: from the third
  exposure a failure takes the model's interval (≥ 1 day) and is due on a day
  boundary. An in-progress card's third exposure is therefore `RELEARNING`
  **with** a positive `intervalDays` — deliberate, not a bug; a *new* card's
  third exposure is `LEARNING` instead. The exposure count comes from
  `review_log` for the `(paperId, questionId)` since `DayBoundary.startOfDay`
  (no counter column), and `FsrsReplay` counts the same way so the statistics
  reconstruct the stored schedule. `StudyStore.exposureInStudyDay` is the live
  source of that count and is covered by `ExposureInStudyDayTest`.
- **21 FSRS-6 weights cannot be converted** to 34 FSRS-7 weights. An upgraded
  install falls back to the defaults with `fsrs_fit_version = 0` and a one-shot
  Settings notice (`SettingsStore.ensureFsrs7`).
- **The card-state rebuild writes only `(stability, stabilityFast, difficulty)`.**
  `dueAt`, `intervalDays`, `reps`, `lapses`, `leech`, `lastReviewedAt` and
  `contentHash` must keep the values the learner already sees; the write is a
  compare-and-set on `lastReviewedAt` so a concurrent review wins.
- **A result that did not train is never stored.** `FsrsTuner.isStorableFit`
  requires `steps > 0` besides a strictly better, finite loss. At `fsrs_items == 0`
  this matches Anki's `compute_params`, which returns the current parameters; for
  `0 < items < 64` it is deliberately stricter, because `fsrs-rs` returns the
  *defaults* there and Anki would adopt them when they score better — which is how
  a thin-data fit could destroy a real one. Do not "fix" this toward Anki. The
  Optimize screen reports storage from the tuner's own flag, never by recomputing
  the loss comparison.
- **A restore does not wait for the rebuild.** `McqRepository.settingsRestore`
  fires it on the process scope, so the restore's success depends on the data and
  settings write alone; a rebuild failure is logged and resumes next launch. Until
  it finishes, un-reached cards schedule from `Sf = S`, and a card reviewed in
  that window keeps the review-derived value (the compare-and-set then skips it).
- `src/test` is split: pure-JVM tests (`FsrsSchedulerTest`, `FsrsOptimizerTest`,
  the parsers, statistics) run on the ARM64 host; anything touching Room or
  SQLite is Robolectric and only CI (x86_64) can run it.

## Anki interoperability

- Export writes a legacy schema-11 `.apkg` with no `meta`, which Anki upgrades on import.
  Deliberate: Anki 11 -> 18 upgrade is the officially supported path for old clients.
- The notetype has three fields: Front, Back, and `mcqapp` (a JSON copy of the question). The
  card template renders only Front and Back; the third field exists so importing a deck back
  recovers options and correct answers exactly, because a readable back field cannot represent
  option boundaries.
- Options go on the **front** of the card, the correct answer(s) and explanation on the back.
- Media in fields is referenced by **filename** (`mcqapp-0.png`), never by the numeric zip
  entry name. Anki keys its import media map by filename, so a numeric `src` never resolves.
- **Import accepts modern packages.** Anki 2.1.50+ writes `collection.anki21` or a
  zstd-compressed `collection.anki21b` (schema 18, `decks.name` using `\x1f` as the folder
  separator) beside a one-note `collection.anki2` compatibility stub. The collection is
  chosen from `meta`: v3 → `anki21b`, v2 → `anki21`, v1/absent → `anki21` else `anki2`,
  with `anki21`/`anki21b`/`anki2` as the fallback. The stub is never imported; the
  collection and the `media` manifest may be zstd frames, decoded by the app's vendored
  pure-Java decoder (`com.mcqapp.data.anki.zstd`; no dependency, no native code, no
  `Unsafe`/`VarHandle`/foreign API), and the manifest is JSON in legacy packages but
  protobuf `MediaEntries` in v3 ones. Both are parsed, and `\x1f` becomes `::` so the
  category tree survives. A stub-first package used to import zero questions and show an
  empty "Imported" dialog.
- **The SM-2 import bridge solves at Anki's historical retention, not the learner's target.**
  `AnkiScheduling.HISTORICAL_RETENTION = 0.9` feeds
  `FsrsScheduler.stateForInterval`, mirroring upstream's
  `memory_state_from_sm2_scalar(w, interval, sm2_retention)` and Anki's
  `historical_retention` deck option (default 0.9). The learner's desired retention
  deliberately does **not** influence an import: moving that slider must never reinterpret
  cards imported yesterday. `fromAnki` takes no retention argument and solves at
  `HISTORICAL_RETENTION` unconditionally.
- **Known limitation: an `.apkg` deck's stored FSRS state is ignored.** Importers do not read
  `cards.data` (`{"s","d","dr","decay","lrt"}`, `anki/rslib/src/storage/card/data.rs`), so a
  deck that itself uses FSRS is re-derived from the interval through the SM-2 bridge — always
  `D = 5`, and a stability the source deck already knew is lost. The column's key names are a
  serde detail rather than a documented format, which is why this is deferred, not done. If it
  is ever implemented, fall back to the bridge whenever `data` is absent or unparsable.

## Plain-JSON export format

- `version` is load-bearing, not decorative: it decides how `scheduling` keys resolve.
  This build writes **2**; a file without the field reads as **1**. Never change the key
  shape without bumping it, and never write a version the reader does not branch on.
- Version 2 keys `scheduling` `"<paperId>\u0000<questionId>"` and additionally writes the
  bare question id **only** when that id has a studied row in exactly one paper
  (`reps > 0 || dueAt > 0`, the exporter's own skip rule). The alias is what lets a build
  from before version 2 restore progress; without it an old install restores nothing and
  says nothing.
- Both readers resolve keys through `CardScheduleMapping.lookupCardSchedule` — one helper,
  because two orderings drifted apart once already. v2 tries composite then bare; v1 tries
  bare then composite. A future version keeps v2 precedence unless a bump says otherwise.
- Accepted costs: the alias roughly doubles the `scheduling` payload for a fully studied
  bank, and compatibility is one-directional — an old reader gets the unambiguous ids and
  silently gets *nothing* for a colliding id, never another card's schedule. The separator
  is a NUL and ids are not sanitised, so a hand-edited id containing a NUL could collapse
  that distinction; that is a crafted-file scenario, not an honest one.

## Hosts and environment

Two hosts have been used. `. /sdcard/repo/apk-build/env.sh` exports `JAVA_HOME`,
`ANDROID_HOME` and `MCQ_AAPT2_OVERRIDE` (the `aapt2` the ARM64 host needs) and
`MCQ_REPO`.

### Termux / Android host (repo at `/sdcard/repo/mcq`)

```bash
. /sdcard/repo/apk-build/env.sh
cd "$MCQ_REPO" && sh gradlew :app:assembleDebug :app:testDebugUnitTest \
  $MCQ_AAPT2_OVERRIDE --no-daemon
```

- The `aapt2FromMavenOverride` is only needed on the Termux host (ARM64); `env.sh` exports it
  (`MCQ_AAPT2`, default `/opt/android-aapt2/aapt2`). GitHub Actions uses the bundled `aapt2`
  and passes no override. The old `/tmp/ncal-aapt2/aapt2` path no longer exists.
- `run-jvm-tests.sh` used to select classes by grepping the source for the **word**
  Robolectric, so a comment could hide a class: `NoDeadCodeTest`, `MistakesTest`,
  `BackupStreamingReadTest`, `ScaledRowStorageTest` and `DownscaledImagesTraversalTest` were
  silently skipped. It now selects on `^import org.robolectric`, so a full local pass covers
  them. If a future edit reintroduces a word-match, the symptom is a lower class count.
- The ARM64 hosts (Termux and the NixOS arm64 host) fail Robolectric tests that open a real
  SQLite database with "The Robolectric native runtime is not supported on Linux (aarch64)".
  CI (x86_64) is authoritative for those. Pure-JVM tests do run locally.
- Never add a test dependency without asking; the project deliberately uses no SQLite driver
  library and calls `android.database.sqlite` under Robolectric instead.
- APK artifacts download to `/sdcard/Download` (see "CI, artifacts and releases").

### NixOS arm64 host (repo at `/home/naresh/repo/mcq`)

There is no `env.sh` at `/sdcard` and no `/sdcard` here; use the repo's own
environment instead (verified 2026-10-23):

```bash
. apk-build/env.sh          # exports JAVA_HOME, ANDROID_HOME, MCQ_AAPT2_OVERRIDE;
                            # runs apk-build/setup-env.sh if pieces are missing
sh gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin \
  $MCQ_AAPT2_OVERRIDE --dependency-verification=lenient --no-daemon
```

- `env.sh`/`setup-env.sh` manage everything under `/tmp/mcq-apk-env` (jdk17, qemu, the
  x86-64 sysroot and the aapt2 wrapper at `/tmp/mcq-apk-env/aapt2/aapt2`). This replaces
  the old ad-hoc `/tmp/ncal-*` layout; a full rebuild after a reboot takes ~6s because the
  Nix store keeps the artifacts. The SDK itself is `/home/naresh/android-sdk` and persists.
- `/opt/android-aapt2/aapt2` does not exist here; the override the scripts produce is
  always `/tmp/mcq-apk-env/aapt2/aapt2`.
- `run-jvm-tests.sh` does not exist on the NixOS arm64 host. Pure-JVM selection: files without
  `^import org.robolectric` that contain `@Test` (helper files break the `--tests` list), with
  **unquoted** class names — zsh passes embedded quotes straight through and Gradle then
  matches nothing. Full local pass ≈237 classes after the round-2 fix batch (the
  guard classes are pure JVM too). The
  `:app:testPureJvm` Gradle task now codifies this selection rule so it no longer
  depends on a shell recipe.
- Robolectric/SQLite tests still fail locally (aarch64); CI remains authoritative.
- APK artifacts download to `/home/naresh/Download`, not `/sdcard/Download`:

```bash
gh run download <run-id> --name app-debug   -D /home/naresh/Download   # -> app-debug.apk
gh run download <run-id> --name app-release -D /home/naresh/Download   # -> app-release.apk
```

## Localisation and user-facing copy

- English is the base locale (`res/values/strings*.xml`); a complete **Hindi**
  translation lives in `res/values-hi/strings*.xml`. Keep names and format
  specifiers identical across locales; plurals need both `one` and `other`.
- Users pick a language in **Settings → Appearance → Language**
  (`AppLanguage`: SYSTEM / ENGLISH / HINDI). SYSTEM follows the device. API 33+
  uses platform per-app locales (`LocaleManager`); older APIs wrap the Activity
  context. User-facing copy is resolved with the selected locale, not just the
  system one.
- All user-facing errors are string resources with plain, friendly wording; raw
  exception detail goes to `Logger` only. Data-layer fallback titles are stable
  **tokens** (stored values must not change because SQL/search compare titles by
  equality) and are resolved to localized display in the UI.

## CI, artifacts and releases

- **Every reported bug ships with a regression test in the same PR** — a unit
  test for logic/data, a UI test for layout/interaction — and it must fail on
  the old behaviour. Owner rule; the portable form is in `AGENTS.md`.
- The workflows are `.github/workflows/build.yml` (build, unit tests, lint, UI
  test) and `.github/workflows/release.yml` (publish on a tag). The release
  workflow signs with the real keystore from CI secrets and uploads
  **`mcq-<tag>-signed.apk`** plus **`mcq-<tag>-signed.apk.sha256`**; it creates
  the release with auto-generated notes. A supervisor caveat, when there is one,
  is prepended afterwards with `gh release edit --notes-file`.
- Every release also needs **user-facing notes** (AGENTS.md owner rule): plain
  language, what changed for the learner, how the UI/behaviour will feel, and a
  concrete before/after example or scenario. The auto-generated PR list is not
  a substitute. Say "nothing user-visible changed" when that is true.
- A release tags the **merge commit** on `main` as `0.0.N`; `0.0.67` is the
  published release (modern Anki `.apkg` import: real collection chosen over the
  compatibility stub, vendored pure-Java zstd, protobuf media manifest, deck
  separators, zero-question report). `0.0.66` added flashcards, keyless
  self-grading, sample reload and a refreshed demo; `0.0.65` the scrollable
  import summary and picture/table/formula answers on reveal, `0.0.64` the
  bookmark-removal clarity and the aligned Study clock, `0.0.63` the scrollable
  export dialogs, `0.0.62` the whole round-2 review pass. Room schema is **29**.
  Round-2 review reports are in `review-reports-2/` (PR #94, merged); the fix
  work is PR #95 (merged).
- **Flashcards.** A question with zero options is a flashcard: front = the stem
  elements/image, back = `explanationElements`/`explanationImage`. The type is
  derived, never stored. **Any question without an answer key** — a zero-option
  card or a keyless MCQ — is self-graded in Study (Show answer, then
  Again/Hard/Good/Easy; `wasCorrect`/`wasGuess` null, `gradeSource` CHOSEN),
  studied only (never sampled into tests), searchable by their back, and their
  back resets the schedule through `CardContent` while an MCQ explanation does
  not. One prompt = one answer: the identity hash is prompt-only, and a changed
  back is an answer update. One-option questions are invalid; importers convert
  them and schema 29 converts any stored ones. The bundled sample bank
  demonstrates each back kind: text, table, formula, image, bilingual and a
  passage-member card, plus the one-option conversion edge.
- `:app:testDebugUnitTest` has stalled at its 10-minute timeout at a **different
  class each run** (`BlueprintPreviewFreshTest`, `Fsrs7UpgradeTest`,
  `SignalQueryTest`). Cause (proven by a CI `jstack` sentinel): a DataStore write
  cancelled mid-flight — `withTimeout` around `dataStore.edit`, on a loaded
  runner — poisons the process-wide write actor (`updateData` runs the caller's
  transform under the caller's own context inside that one loop), so every later
  DataStore call parks forever with the whole JVM otherwise idle. **Never cancel
  a DataStore write**: bound the *wait* instead (`runOffMainWhilePumping` never
  cancels or interrupts its block). `DataStoreWriteCancellationGuardTest` pins
  this; the suite runs as a 4-way shard matrix, and the JVM is recycled every 40
  classes. Do not raise the task timeout to get past a stall — that hides the
  poison.
- CI uploads five artifacts (`if: always()`): `app-debug`, `app-release`,
  `lint-report`, `ui-test-results`, and the unit-test results as a 4-way
  matrix `test-results-shard-0..3` (`apk-build/test-shards.sh` partitions the
  363 classes; a Robolectric lost-wakeup wedge in one shard reports alone and
  dumps its thread stacks instead of holding the branch). After every push,
  download both APKs and keep them under these exact names:

```bash
gh run download <run-id> --name app-debug   -D /sdcard/Download   # -> app-debug.apk
gh run download <run-id> --name app-release -D /sdcard/Download   # -> app-release.apk
# unit-test results: repeat --name test-results-shard-0 .. -3
```

- **Debug builds are signed with the committed `apk-build/debug.keystore`.** CI
  runners are ephemeral, so a freshly generated debug key each run changed the
  signer and a new CI debug APK could not install over the previous debug
  install ("App not installed"). The key is a *debug* key: deliberately public
  and non-secret (standard `android`/`androiddebugkey` credentials, cert
  SHA-256 `74:7D:D7:74:F5:EC:8E:4E:88:E0:C5:AB:65:6D:5D:95:2C:67:FA:06:22:BF:10:48:48:82:DC:E9:F5:A9:81:23`),
  which is why `.gitignore` carries a narrow exception for exactly that path
  while every other `*.keystore`/`*.jks` stays ignored. **Never add a similar
  exception for a release key.** Replacing the committed debug key changes the
  debug signer, so the already-installed debug app needs one uninstall first.
- `/sdcard/Download/app-debug.apk` and `/sdcard/Download/app-release.apk` are always the
  latest build. The debug variant has `applicationIdSuffix = ".debug"`, so it installs as
  `com.mcqapp.debug` **beside** the release (`com.mcqapp`) with its own data directory and
  FileProvider authority; the two can be installed at the same time and neither replaces
  the other. A release APK still only installs over a release with the same signing key,
  and the CI release is signed with a throwaway key.
- **The signed release asset from the tag is the genuinely signed one.** The CI
  `app-release` artifact is built with a throwaway key and will not install over
  a real release install; `mcq-<tag>-signed.apk` from the GitHub release uses the
  real keystore and does.
- `gh` here lacks `--clobber`: download to a scratch directory, then copy over
  the final files. Downloads are slow on this host — run them in the background.

## GitHub access

- **GitHub SSH is set up on this host** (`~/.ssh/id_ed25519`, `~/.ssh/config`, key
  `termux-mcq-20261115` on the account), so `origin` pushes over `git@github.com:npnpatidar/mcq.git`
  work from git and lazygit. `gh auth setup-git` is also configured, so HTTPS still works as a
  fallback. A new device only needs `sh github-ssh-bootstrap.sh` from the bundle in
  `/root/.config/github-auth/` (also on `/sdcard/repo/.github-auth/`): it generates a per-device key,
  logs `gh` in with the bundled token, and registers the key on GitHub with no browser step.
- **Never point a branch's upstream at a raw URL.** `branch.<name>.remote` must be `origin`;
  a URL there makes git treat the URL as a remote name and lazygit's push fails with
  "could not read Username for 'https://github.com'".
- On this ARM64 host, Robolectric/SQLite tests cannot run, so a green local compile is not
  a green CI: the first CI run is where the new Room-backed tests actually execute.
