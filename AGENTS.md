# Project notes for agents

Android MCQ app (Kotlin, Jetpack Compose, Room). Repo `git@github.com:npnpatidar/mcq.git`, branch `main`.

## Build and test

```bash
cd /sdcard/repo/mcq && ANDROID_HOME=/opt/android-sdk ANDROID_SDK_ROOT=/opt/android-sdk \
  sh gradlew :app:assembleDebug :app:testDebugUnitTest \
  -Pandroid.aapt2FromMavenOverride=/tmp/ncal-aapt2/aapt2 --no-daemon
```

- The `aapt2FromMavenOverride` is only needed on this ARM64 host; GitHub Actions uses the
  bundled `aapt2` and does not pass it.
- This host is Linux ARM64, so Robolectric tests that open a real SQLite database fail with
  "The Robolectric native runtime is not supported on Linux (aarch64)". CI (x86_64) is
  authoritative for those. Pure-JVM tests do run locally.
- Never add a test dependency without asking; the project deliberately uses no SQLite driver
  library and calls `android.database.sqlite` under Robolectric instead.

## After every push

CI uploads the debug APK as the `app-debug` artifact (`if: always()`). When a GitHub Actions
build runs, download the APK to `/sdcard/Download`:

```bash
gh run download <run-id> --name app-debug -D /sdcard/Download
```

Keep it as `/sdcard/Download/app-debug.apk`.

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
