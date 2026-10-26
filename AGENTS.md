# Project notes for agents

Android MCQ app (Kotlin, Jetpack Compose, Room). Repo `git@github.com:npnpatidar/mcq.git`, branch `main`.

## Branching (owner rule)

- Implement everything on a feature branch cut from `main` (`feature/<topic>`). `main`
  receives merged PRs only — never commit to it directly, not even docs.
- Open a PR for every branch (draft until the owner says go); the owner decides when it
  merges and when a release is cut.
- Releases tag the merge commit on `main` as `0.0.N`, in the existing shape: asset
  `mcq-<tag>-signed.apk` plus its `.sha256`, notes auto-generated with the PR links.

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
  latest build; the release APK is signed with CI's throwaway key, so installing it over a
  debug build (or vice versa) needs an uninstall first.

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
