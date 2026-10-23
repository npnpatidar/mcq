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

- The `aapt2FromMavenOverride` is only needed on this ARM64 host; `env.sh` exports it
  (`MCQ_AAPT2`, default `/opt/android-aapt2/aapt2`). GitHub Actions uses the bundled `aapt2`
  and passes no override. The old `/tmp/ncal-aapt2/aapt2` path no longer exists.
- `run-jvm-tests.sh` used to select classes by grepping the source for the **word**
  Robolectric, so a comment could hide a class: `NoDeadCodeTest`, `MistakesTest`,
  `BackupStreamingReadTest`, `ScaledRowStorageTest` and `DownscaledImagesTraversalTest` were
  silently skipped. It now selects on `^import org.robolectric`, so a full local pass covers
  them. If a future edit reintroduces a word-match, the symptom is a lower class count.
- This host is Linux ARM64, so Robolectric tests that open a real SQLite database fail with
  "The Robolectric native runtime is not supported on Linux (aarch64)". CI (x86_64) is
  authoritative for those. Pure-JVM tests do run locally.
- Never add a test dependency without asking; the project deliberately uses no SQLite driver
  library and calls `android.database.sqlite` under Robolectric instead.

## This host (NixOS aarch64, repo at /home/naresh/repo/mcq)

The `/sdcard` block above is the other host (Android/Termux). On this machine there is no
`env.sh` and no `/sdcard`; use these direct exports instead (verified 2026-10-23):

```bash
export JAVA_HOME=/tmp/ncal-sdk-links/jdk17
export ANDROID_HOME=/home/naresh/android-sdk
export ANDROID_SDK_ROOT=/home/naresh/android-sdk
sh gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin \
  -Pandroid.aapt2FromMavenOverride=/tmp/ncal-aapt2/aapt2 \
  --dependency-verification=lenient --no-daemon
```

- The working aapt2 override here is `/tmp/ncal-aapt2/aapt2` — on this host it exists and
  works (the "no longer exists" note above applied to the `/sdcard` host). `/opt/android-aapt2/aapt2`
  does not exist here.
- All of `/tmp/ncal-*` is volatile: after a reboot rebuild the jdk17 link, qemu and the x86-64
  sysroot per `BUILDING.md` §9.1/§9.3, then the wrapper per `BUILDING.md` §10. The SDK at
  `/home/naresh/android-sdk` persists.
- `run-jvm-tests.sh` does not exist on this host. Pure-JVM selection: files without
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
