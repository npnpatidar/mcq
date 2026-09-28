# Building an APK on this machine

This guide records the exact local build path used for this checkout. The normal Android build is the preferred path; the QEMU section is only needed because this machine is ARM64 while the installed Android build-tools binaries are x86-64.

## 1. Known-good environment

The following is the environment used to produce the APK:

- Workspace: `/sdcard/repo/mcq`
- Host architecture: `aarch64` (`uname -m`)
- Java: OpenJDK 21; the Android build also supports the CI JDK 17 configuration
- Android SDK: `/opt/android-sdk`
- Compile SDK: 37
- Target SDK: 36
- Build tools: 37.0.0
- Gradle: the tracked wrapper, 9.6.1 (a distribution is also pre-installed at `/opt/gradle-9.6.1` and can be used to generate the wrapper: `/opt/gradle-9.6.1/bin/gradle wrapper --gradle-version 9.6.1`)
- AGP: 9.4.1 (AGP 8.x is NOT compatible with Gradle 9.6.1; see section 4.4)
- Kotlin: not applied as a plugin; AGP 9 has built-in Kotlin support (bundles Kotlin 2.2.10)
- KSP: 2.2.10-2.0.2 (must match the Kotlin version bundled with AGP)
- QEMU: `/usr/bin/qemu-x86_64`

The project does not require a separately installed Gradle distribution. Use `./gradlew`.

Note: the `/sdcard` filesystem is mounted `noexec`, so the `gradlew` script cannot be made executable there. Invoke it via `sh gradlew` instead.

## 2. Enter the workspace and configure the SDK

```sh
cd /sdcard/repo/mcq

export ANDROID_HOME=/opt/android-sdk
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

java -version
./gradlew --version
```

Install or repair the SDK packages if needed:

```sh
yes | sdkmanager --licenses >/dev/null
sdkmanager "platforms;android-37" "build-tools;37.0.0"
```

The SDK packages are already present on this machine, so the install commands normally report that they are installed.

## 3. Build on a normal x86-64 Linux/macOS/Windows host

On a host where the Android SDK tools can run natively:

```sh
cd /sdcard/repo/mcq
export ANDROID_HOME=/opt/android-sdk       # use the SDK path on the host
export ANDROID_SDK_ROOT="$ANDROID_HOME"

./gradlew :app:assembleDebug
```

The installable debug APK is written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Verify it and calculate a checksum:

```sh
ls -lh app/build/outputs/apk/debug/app-debug.apk
sha256sum app/build/outputs/apk/debug/app-debug.apk
```

Install it on a connected device or emulator:

```sh
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The debug APK is signed with the standard Android debug keystore. It is not a Play Store release artifact.

## 4. ARM64 compatibility issue on this machine

The Android SDK package supplied on this machine contains x86-64 AAPT2 binaries. The host is ARM64, so a direct build fails with an error similar to:

```text
AAPT2 ... Daemon startup failed
Cannot run program .../aapt2: Exec failed, error: 2
```

This is a host/tool-architecture mismatch, not an application or Gradle dependency error. The supported solutions are to use an x86-64 CI runner, or run AAPT2 through `qemu-x86_64` locally.

### 4.1 Install the emulation prerequisites

On Debian/Ubuntu ARM64, enable the amd64 package architecture and install the user-mode emulator plus the x86 runtime libraries:

```sh
sudo dpkg --add-architecture amd64
sudo apt-get update
sudo apt-get install qemu-user libc6-amd64-cross libgcc-s1:amd64
```

Confirm the tools and runtime exist. Note: on this Debian version the x86-64 loader lives under `lib/`, not directly in the prefix directory:

```sh
command -v qemu-x86_64
ls -l /usr/x86_64-linux-gnu/lib/ld-linux-x86-64.so.2
ls -l /usr/lib/x86_64-linux-gnu/libgcc_s.so.1
```

If the distribution does not provide `libgcc-s1:amd64`, use an isolated copy of the matching Debian package. This is the tested fallback for this machine's Debian archive; verify the package checksum before using it:

```sh
AAPT2_LIBS=/tmp/ncal-aapt2-libs
mkdir -p "$AAPT2_LIBS"

curl -fL -o /tmp/ncal-libgcc-s1.deb \
  https://deb.debian.org/debian/pool/main/g/gcc-14/libgcc-s1_14.2.0-19_amd64.deb

printf '%s  %s\n' \
  '3c71917b490d1a17aed43196a2787a256ecf060526cdb20216a74bedc061b150' \
  /tmp/ncal-libgcc-s1.deb | sha256sum -c -

dpkg-deb -x /tmp/ncal-libgcc-s1.deb "$AAPT2_LIBS"
```

The isolated path used successfully here was:

```text
/tmp/ncal-aapt2-libs/usr/lib/x86_64-linux-gnu/libgcc_s.so.1
```

### 4.2 Create the AAPT2 wrapper

AGP requires the custom executable to be named `aapt2`. Create a temporary wrapper; do not add machine-specific paths to the repository. Note: the qemu `-L` prefix must be `/usr/x86_64-linux-gnu` (qemu resolves the loader at `<prefix>/lib64/ld-linux-x86-64.so.2`, which is where this Debian puts it):

```sh
AAPT2_WRAPPER=/tmp/ncal-aapt2/aapt2
mkdir -p "$(dirname "$AAPT2_WRAPPER")"

cat > "$AAPT2_WRAPPER" <<'EOF'
#!/bin/sh
export LD_LIBRARY_PATH="/tmp/ncal-aapt2-libs/usr/lib/x86_64-linux-gnu${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
exec qemu-x86_64 -L /usr/x86_64-linux-gnu \
  /opt/android-sdk/build-tools/37.0.0/aapt2 "$@"
EOF

chmod 700 "$AAPT2_WRAPPER"
"$AAPT2_WRAPPER" version
```

The version command should print an Android Asset Packaging Tool version. If it reports a missing `libgcc_s.so.1`, check the extracted path and `LD_LIBRARY_PATH` in the wrapper. If it reports `Could not open '/lib64/ld-linux-x86-64.so.2'`, the `-L` prefix is wrong; use `/usr/x86_64-linux-gnu` (not `/usr/x86_64-linux-gnu/lib`).

### 4.3 Build with the wrapper

Pass the AAPT2 override on the Gradle command line:

```sh
cd /sdcard/repo/mcq
export ANDROID_HOME=/opt/android-sdk
export ANDROID_SDK_ROOT="$ANDROID_HOME"

./gradlew :app:assembleDebug \
  -Pandroid.aapt2FromMavenOverride=/tmp/ncal-aapt2/aapt2 \
  --no-daemon
```

The same override can be used for the resource-dependent verification tasks:

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug \
  -Pandroid.aapt2FromMavenOverride=/tmp/ncal-aapt2/aapt2 \
  --no-daemon
```

The override is intentionally a command-line option. It is not written to `gradle.properties`, CI, or source control, so the project remains portable.

On this machine, the following exact command completed successfully and produced the APK:

```sh
ANDROID_HOME=/opt/android-sdk ANDROID_SDK_ROOT=/opt/android-sdk \
sh gradlew :app:testDebugUnitTest :app:assembleDebug \
  -Pandroid.aapt2FromMavenOverride=/tmp/ncal-aapt2/aapt2 \
  --no-daemon
```

The debug APK produced by this build is approximately 21 MB:

```text
app/build/outputs/apk/debug/app-debug.apk
4021cfbbb1bea08ffa150461d21aef81253722fcedca839186a23b77a23ca22e  app/build/outputs/apk/debug/app-debug.apk
```

AGP prints an experimental warning for `android.aapt2FromMavenOverride`; it is expected for this command-line-only compatibility override and does not indicate a build failure.

### 4.4 Toolchain versions: Gradle 9.6.1 requires AGP 9.x

This build was initially attempted with AGP 8.13.2 and failed during project configuration:

```text
Caused by: org.gradle.api.GradleException: Plugin 'com.android.internal.application'
relies on 'org.gradle.api.problems.internal.InternalProblems', a Gradle internal
API that was removed in Gradle 9.6.0. Update the plugin to a version that no longer
uses Gradle internal APIs, or use Gradle 9.5.
```

Fix: use AGP 9.4.1 (the latest 9.x). AGP 9 introduces built-in Kotlin support, which requires these additional changes (all were hit during this build):

1. **Remove the `org.jetbrains.kotlin.android` plugin** from the root and app build files. AGP 9 fails with: `The 'org.jetbrains.kotlin.android' plugin is no longer required for Kotlin support since AGP 9.0.`
2. **Match compiler-plugin versions to the Kotlin bundled with AGP.** AGP 9.4.1 bundles Kotlin 2.2.10, so `org.jetbrains.kotlin.plugin.compose` and `org.jetbrains.kotlin.plugin.serialization` must be version `2.2.10` (not the latest stable Kotlin release).
3. **Migrate `android.kotlinOptions { }` to `kotlin { compilerOptions { } }`** placed inside the `android { }` block. `jvmTarget` defaults to `compileOptions.targetCompatibility` and can be omitted.
4. **Use KSP 2.2.10-2.0.2** (published as `com.google.devtools.ksp:symbol-processing-gradle-plugin`, plugin id `com.google.devtools.ksp`). The older KSP 1.5.x line is incompatible with AGP 9.
5. **Set `android.disallowKotlinSourceSets=false` in `gradle.properties`.** KSP still registers its generated sources via the `kotlin.sourceSets` DSL, which AGP 9 disallows by default. Without this setting the build fails with: `Using kotlin.sourceSets DSL to add Kotlin sources is not allowed with built-in Kotlin.`

The working root `build.gradle.kts` plugin block:

```kotlin
plugins {
    id("com.android.application") version "9.4.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.10" apply false
    id("com.google.devtools.ksp") version "2.2.10-2.0.2" apply false
}
```

## 5. Confirm the generated APK

After a successful ARM64/QEMU build:

```sh
ls -lh app/build/outputs/apk/debug/app-debug.apk
sha256sum app/build/outputs/apk/debug/app-debug.apk
```

The checksum changes whenever source, dependencies, or the build toolchain changes. Always calculate a new checksum for the artifact you are distributing.

Install it with:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 6. Release build

Release builds use R8/resource shrinking and require explicit version properties. Android/Play version codes are limited to `1..2100000000`; the protected workflow uses the `RELEASE_VERSION_CODE` environment secret for tag releases and requires an explicit code for manual releases.

```sh
./gradlew :app:bundleRelease \
  -PappVersionCode=1 \
  -PappVersionName=1.0.0 \
  -PreleaseStoreFile=/path/to/release.jks \
  -PreleaseStorePassword="$RELEASE_STORE_PASSWORD" \
  -PreleaseKeyAlias="$RELEASE_KEY_ALIAS" \
  -PreleaseKeyPassword="$RELEASE_KEY_PASSWORD" \
  --no-daemon
```

On ARM64, add the AAPT2 override shown above. The release bundle is written under:

```text
app/build/outputs/bundle/release/
```

Do not commit a keystore or signing passwords. The protected release workflow expects these values as repository/environment secrets, requires `RELEASE_CERT_SHA256` and `RELEASE_VERSION_CODE` to match the protected release, creates a GitHub artifact attestation, and uploads the AAB, mapping file, checksum, and source-bound provenance file.

## 7. CI build

`.github/workflows/build-apk.yml` runs on an x86-64 GitHub runner and does not need QEMU:

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

The workflow uploads the debug APK as the `ncal-debug-apk` artifact. The release workflow builds the signed AAB and R8 mapping artifact.

## 8. Troubleshooting

### `SDK location not found`

Set both variables before invoking Gradle:

```sh
export ANDROID_HOME=/opt/android-sdk
export ANDROID_SDK_ROOT="$ANDROID_HOME"
```

### `Exec format error` or AAPT2 daemon startup failure

The host is ARM64 and the SDK binary is x86-64. Use the QEMU wrapper and pass `-Pandroid.aapt2FromMavenOverride=...`, or build on x86-64 CI/another machine.

### `Custom AAPT2 location does not point to an AAPT2 executable`

Ensure the wrapper filename is exactly `aapt2`, the file is executable, and the path is absolute:

```sh
chmod 700 /tmp/ncal-aapt2/aapt2
```

### `qemu-x86_64: Could not open ... ld-linux-x86-64.so.2`

Install `libc6-amd64-cross`, then verify `/usr/x86_64-linux-gnu/lib/ld-linux-x86-64.so.2` exists. The qemu `-L` prefix must be `/usr/x86_64-linux-gnu` so qemu finds the loader at `<prefix>/lib64/ld-linux-x86-64.so.2`.

### `libgcc_s.so.1: cannot open shared object file`

Install the amd64 `libgcc-s1` package or use the isolated package extraction in section 4.1. Confirm the wrapper's `LD_LIBRARY_PATH` points at the extracted `usr/lib/x86_64-linux-gnu` directory.

### `Plugin 'com.android.internal.application' relies on 'org.gradle.api.problems.internal.InternalProblems'`

AGP 8.x is incompatible with Gradle 9.6.1. Upgrade to AGP 9.4.1 and apply all the built-in-Kotlin changes listed in section 4.4.

### `The 'org.jetbrains.kotlin.android' plugin is no longer required for Kotlin support since AGP 9.0`

Remove the `org.jetbrains.kotlin.android` plugin from the root and app build files; AGP 9 compiles Kotlin sources itself.

### `Using kotlin.sourceSets DSL to add Kotlin sources is not allowed with built-in Kotlin`

KSP registers generated sources through the `kotlin.sourceSets` DSL. Add `android.disallowKotlinSourceSets=false` to `gradle.properties`, and make sure you are on KSP 2.2.10-2.0.2 (KSP 1.5.x does not support AGP 9).

### Dependency verification failure

Do not disable verification for an untrusted build. Use the committed `gradle/verification-metadata.xml`, the tracked wrapper checksum, and a trusted repository. If a legitimate dependency change is being made, review and regenerate metadata intentionally rather than using a blanket bypass.

### No APK is found

Check the task result and use:

```sh
./gradlew :app:assembleDebug --info
ls -lh app/build/outputs/apk/debug/
```

A successful build must create `app/build/outputs/apk/debug/app-debug.apk`.
