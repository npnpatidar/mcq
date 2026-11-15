# Project notes for agents

Android MCQ app (Kotlin, Jetpack Compose, Room). Repo `git@github.com:npnpatidar/mcq.git`, branch `main`.

## Branching (owner rule)

- Implement everything on a feature branch cut from `main` (`feature/<topic>`). `main`
  receives merged PRs only — never commit to it directly, not even docs.
- Open a PR for every branch (draft until the owner says go); the owner decides when it
  merges and when a release is cut.
- Releases tag the merge commit on `main` as `0.0.N`, in the existing shape: asset
  `mcq-<tag>-signed.apk` plus its `.sha256`, notes auto-generated with the PR links.

## Supervisor workflow (owner rule, set 2026-11-14)

- The supervising agent does **not** write product code: no edits under `app/`, no build
  files, no schema. Every code change goes to an implementation subagent with a scoped brief
  (intent, files, constraints, acceptance criteria); the supervisor reviews the result.
- Markdown (reports, commit messages, PR text) and read-only inspection stay with the
  supervisor.
- Every code change is reviewed by a **separate** reviewer subagent — never the author.
  One area-matched reviewer per batch by default; **three** specialist reviewers
  (design/domain, implementation/integration, tests/docs) for anything significant,
  including the grading rule, the Room schema, the backup format or first-run defaults.
- Subagents run **strictly sequentially**, never in parallel, even independent ones.
- **A subagent killed by a provider quota error is retried, not abandoned.** Try once to
  continue the same session (call `subagent` with its `sessionID` and the full remaining
  brief); if that session dies again, launch a **fresh** subagent carrying the whole brief,
  because the dead session can stay pinned to the exhausted provider. A quota death is an
  infrastructure failure, not a result: never skip, shrink or downgrade that reviewer pass
  to get past it.
- **Reviews verify against official upstream references, never against this repo's own
  design documents.** Plans, blueprints, audits and `ChatGPT_*.md` drafts written here can
  be wrong — a plan agreeing with the code proves nothing. Check algorithms against the
  upstream source or spec (for FSRS: `open-spaced-repetition/fsrs-rs`, `ts-fsrs`, the FSRS
  wiki, the Anki manual and Anki's FSRS integration), and platform claims against the
  official docs (Android/Room, Kotlin, Gradle). Cite the URL next to every external claim,
  and when upstream and a local document disagree, report the disagreement instead of
  assuming the local one is right. The owner rules in this file are authoritative as
  *product decisions*, but they are not evidence about what an algorithm does.
- Behaviour changes, user-facing copy, scope and release state are the owner's call. When
  the owner is away and a decision is unavoidable, launch three decision subagents
  sequentially with the identical question, adopt the convergent answer, and report the
  decision and the split afterwards.

## Product premises (owner rule, set 2026-11-15)

Judge behaviour, find bugs, review and recommend under these premises. A finding that only
exists because one of them is false is not a defect and must not drive code or copy changes.

- **The user is honest.** Nobody is gaming the app, farming Easy grades, or looking for a
  loophole. Do not design, test or review for adversarial use.
- **Normal-speed learners.** This is an everyday study app, not a speed test. A pace under
  one or two seconds per card is rare and not a population to design for; a rule whose only
  benefit goes to a sub-second speed-runner is not worth its complexity.
- **No clock games.** Do not assume a learner stops the clock, reads the question, then
  restarts it before answering. The pause control exists for interruptions. A scenario built
  on pausing to think *around* the timer is out of scope, so evidence stored as `0 s` should
  be read as a genuinely quick answer, not as a paused one.

## Build and test

```bash
# Source the host environment first: it exports JAVA_HOME, ANDROID_HOME and
# MCQ_AAPT2_OVERRIDE (the aapt2 the ARM64 host needs).
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

## NixOS arm64 host (repo at /home/naresh/repo/mcq)

The `/sdcard` block above is the other host (Android/Termux). On the NixOS arm64 host there
is no `env.sh` at `/sdcard` and no `/sdcard`; use the repo's own environment instead
(verified 2026-10-23):

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
  matches nothing. Full local pass ≈124 classes.
- Robolectric/SQLite tests still fail locally (aarch64); CI remains authoritative.
- APK artifacts download to `/home/naresh/Download`, not `/sdcard/Download`:

```bash
gh run download <run-id> --name app-debug   -D /home/naresh/Download   # -> app-debug.apk
gh run download <run-id> --name app-release -D /home/naresh/Download   # -> app-release.apk
```

## After every push

CI uploads the APKs as artifacts (`if: always()`). When a GitHub Actions build runs, download
both to `/sdcard/Download` and keep them under these exact names:

```bash
gh run download <run-id> --name app-debug   -D /sdcard/Download   # -> app-debug.apk
gh run download <run-id> --name app-release -D /sdcard/Download   # -> app-release.apk
```

- `/sdcard/Download/app-debug.apk` and `/sdcard/Download/app-release.apk` are always the
  latest build. The debug variant has `applicationIdSuffix = ".debug"`, so it installs as
  `com.mcqapp.debug` **beside** the release (`com.mcqapp`) with its own data directory and
  FileProvider authority; the two can be installed at the same time and neither replaces
  the other. A release APK still only installs over a release with the same signing key,
  and the CI release is signed with a throwaway key.
- **Monitor a run in the background, never in the foreground.** A build takes ~8 minutes, so
  `gh run watch <run-id> --exit-status` runs backgrounded; blocking on it foreground (or
  sleeping and re-polling `gh run list`) wastes the turn:

  ```bash
  gh run watch <run-id> --exit-status --interval 30 >/tmp/opencode/run-watch.log 2>&1
  ```

  Then do other work or end the turn, and download the APKs when the completion notice
  arrives.
- **A PR with merge conflicts gets no `pull_request` run at all** — not a failing one, none:
  GitHub cannot build the test-merge ref, so the workflow never starts and `gh run list`
  stays empty for the branch. Rebase onto `main` first, then expect the run.
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

## Scheduling (FSRS-7)

- The engine is FSRS-7: **34 weights**, two stability traces (`stability` slow,
  `stabilityFast` fast) and a difficulty-modulated *mixture* forgetting curve.
  The defaults and the parity vector are pinned in `FsrsSchedulerTest`.
- **`stabilityFast == 0` means "unknown", not "zero".** Imported cards and rows
  the rebuild has not reached carry it; `FsrsScheduler.fastStabilityOf` collapses
  those onto `stability`. Never write a fabricated `Sf` (the reference's 0.8×
  ratio is legitimate only for a first exposure or an `Again`).
- **Stability is not an interval.** `R(t = S)` is not the target retention, so
  every due date comes from `intervalForRetention`'s numerical solve. Do not
  render `S` with a `d` suffix, and do not set `S = interval` on import (the
  Anki path bisects the mixed curve instead).
- **No short-term branch.** Elapsed time stays fractional on purpose; rounding it
  to whole days changes the model. Only the resulting interval is whole days.
- **A failed card relearns in the sitting, but only three times a study day.**
  `FsrsScheduler.MAX_EXPOSURES_PER_STUDY_DAY` bounds the loop: from the third
  exposure a failure takes the model's interval (≥ 1 day) and is due on a day
  boundary. That row is therefore `RELEARNING` **with** a positive
  `intervalDays` — deliberate, not a bug. The exposure count comes from
  `review_log` for the `(paperId, questionId)` since `DayBoundary.startOfDay`
  (no counter column), and `FsrsReplay` counts the same way so the statistics
  reconstruct the stored schedule.
- **21 FSRS-6 weights cannot be converted** to 34 FSRS-7 weights. An upgraded
  install falls back to the defaults with `fsrs_fit_version = 0` and a one-shot
  Settings notice (`SettingsStore.ensureFsrs7`).
- **The card-state rebuild writes only `(stability, stabilityFast, difficulty)`.**
  `dueAt`, `intervalDays`, `reps`, `lapses`, `leech`, `lastReviewedAt` and
  `contentHash` must keep the values the learner already sees; the write is a
  compare-and-set on `lastReviewedAt` so a concurrent review wins.
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
