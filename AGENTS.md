# Agent notes — Android project

These notes are **project-agnostic**: they apply to any Android/Kotlin project
this agent works on. Anything specific to one app — its product premises, its
host setup, its scheduling model, its file formats — belongs in that project's
`PROJECT_NOTES.md`. This repository has one; read it before touching anything
domain-specific.

## Before you start

- Read the project's `PROJECT_NOTES.md` (if present) before the first change. It
  carries the owner rules for that product and the facts that are easy to get
  wrong.
- Owner rules in that file are authoritative as *product decisions*. They are
  **not** evidence about what an algorithm does — see "Reviews" below.

## Branching (owner rule)

- Implement everything on a feature branch cut from `main` (`feature/<topic>`).
  `main` receives merged PRs only — never commit to it directly, not even docs.
- Open a PR for every branch (draft until the owner says go); the owner decides
  when it merges and when a release is cut.
- Releases tag the merge commit on `main` as `0.0.N`. The asset names and the
  release-notes shape are the project's own convention; see `PROJECT_NOTES.md`.
- **Every release must publish user-facing notes** alongside the auto-generated
  change list. Write them in plain language: what changed for the person using
  the app, how the UI or behaviour will feel different, and at least one concrete
  before/after example or short scenario. Avoid implementation terms (ViewModels,
  schemas, migrations) — describe what the user sees and does. If nothing user-visible
  changed, say so explicitly. Keep the project's exact notes shape in `PROJECT_NOTES.md`.

## Supervisor workflow (owner rule)

- The supervising agent does **not** write product code: no edits under the app
  module, no build files, no schema. Every code change goes to an implementation
  subagent with a scoped brief (intent, files, constraints, acceptance criteria);
  the supervisor reviews the result.
- Markdown (reports, commit messages, PR text) and read-only inspection stay with
  the supervisor.
- Every code change is reviewed by a **separate** reviewer subagent — never the
  author. One area-matched reviewer per batch by default; **three** specialist
  reviewers (design/domain, implementation/integration, tests/docs) for anything
  significant — a domain rule, the database schema, a backup or export format,
  or first-run defaults.
- Independent subagents may run **in parallel, up to 10 at a time**. A reviewer
  still runs only after the implementation it reviews has finished; give every
  agent a self-contained brief and never let two agents edit the same files.
- **A subagent killed by a provider quota error is retried, not abandoned.** Try
  once to continue the same session (call `subagent` with its `sessionID` and the
  full remaining brief); if that session dies again, launch a **fresh** subagent
  carrying the whole brief, because the dead session can stay pinned to the
  exhausted provider. A quota death is an infrastructure failure, not a result:
  never skip, shrink or downgrade that reviewer pass to get past it.
- **Reviews verify against official upstream references, never against the repo's
  own design documents.** Plans, blueprints, audits and `ChatGPT_*.md` drafts
  written in a project can be wrong — a plan agreeing with the code proves
  nothing. Check algorithms against the upstream source or spec (for a
  spaced-repetition engine: the upstream implementation, its wiki and the
  reference application's integration), and platform claims against the official
  docs (Android/Room, Kotlin, Gradle). Cite the URL next to every external
  claim, and when upstream and a local document disagree, report the
  disagreement instead of assuming the local one is right.
- Behaviour changes, user-facing copy, scope and release state are the owner's
  call. When the owner is away and a decision is unavoidable, launch three
  decision subagents in parallel with the identical question, adopt the
  convergent answer, and report the decision and the split afterwards.

## Build and test (Android)

- **Every reported bug gets a regression test in the same change.** A unit test
  when the defect is logic/data, a UI test when it is layout/interaction. The
  test must fail on the old behaviour — "fixed and manually checked" is not
  done. Owner rule.
- Use the project's own environment script if it has one. It usually exports
  `JAVA_HOME`, `ANDROID_HOME` and any host-specific tool override; see
  `PROJECT_NOTES.md` for this project's script and paths.
- The usual gates: `sh gradlew :app:assembleDebug :app:testDebugUnitTest`.
- **A green local compile is not a green CI.** Robolectric tests that open a real
  SQLite database cannot run on ARM64 Linux ("The Robolectric native runtime is
  not supported on Linux (aarch64)"). CI on x86_64 is authoritative for those;
  pure-JVM tests do run locally.
- ARM64 hosts sometimes need an `aapt2` override. The project's environment
  script supplies it; CI uses the bundled `aapt2` and passes no override.
- Never add a test dependency without asking.
- When selecting a subset of tests for a local run, pick classes by an import
  match (`^import org.robolectric`), not by a word match — a comment containing
  the word can silently hide a class. A lower class count than expected is the
  symptom.

## Git and GitHub hygiene

- **Never point a branch's upstream at a raw URL.** `branch.<name>.remote` must
  be `origin`; a URL there makes git treat the URL as a remote name and pushes
  fail with "could not read Username for 'https://github.com'".
- **Monitor a run in the background, never in the foreground.** A build takes
  minutes, so `gh run watch <run-id> --exit-status` runs backgrounded; blocking
  on it in the foreground (or sleeping and re-polling `gh run list`) wastes the
  turn:

  ```bash
  gh run watch <run-id> --exit-status --interval 30 >/tmp/opencode/run-watch.log 2>&1
  ```

  Then do other work or end the turn, and act when the completion notice
  arrives.
- **A PR with merge conflicts gets no `pull_request` run at all** — not a failing
  one, none: the workflow cannot build the test-merge ref, so it never starts
  and `gh run list` stays empty for the branch. Rebase onto `main` first, then
  expect the run.
- `gh run list --commit` is unreliable for `pull_request` runs; resolve run ids
  with `gh run list --branch <branch>`.
- After every push, CI uploads the build artifacts (`if: always()`). Download
  them and keep them under the names the project uses: `app-debug`, `app-release`,
  `test-results`, `lint-report`, `ui-test-results` (see `PROJECT_NOTES.md`):

  ```bash
  gh run download <run-id> --name app-debug   -D <download-dir>   # -> app-debug.apk
  gh run download <run-id> --name app-release -D <download-dir>   # -> app-release.apk
  gh run download <run-id> --name test-results   -D <download-dir>
  gh run download <run-id> --name lint-report   -D <download-dir>
  gh run download <run-id> --name ui-test-results -D <download-dir>
  ```

  `gh` may lack `--clobber`: download to a scratch directory, then copy over the
  final files.

## Writing agent notes

- Keep this file portable: process, tooling and review rules only.
- Put an app's own facts in its `PROJECT_NOTES.md` and link to them from here.
- Keep working documents (plans, audits, blueprints) out of the repository
  unless the owner wants them tracked — they go stale and later agents treat
  them as truth.
