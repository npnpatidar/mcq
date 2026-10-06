# Building an APK on this machine

This guide records the exact local build path used for this checkout. The normal Android build is the preferred path; the QEMU section is only needed because this machine is ARM64 while the installed Android build-tools binaries are x86-64.

## 1. Known-good environment

The following is the environment used to produce the APK:

- Workspace: `/root/ncal`
- Host architecture: `aarch64` (`uname -m`)
- Java: OpenJDK 21; the Android build also supports the CI JDK 17 configuration
- Android SDK: `/opt/android-sdk`
- Compile SDK: 37
- Target SDK: 36
- Build tools: 37.0.0
- Gradle: the tracked wrapper, 9.6.1
- QEMU: `/usr/bin/qemu-x86_64`

The project does not require a separately installed Gradle distribution. Use `./gradlew`.

## 2. Enter the workspace and configure the SDK

```sh
cd /root/ncal

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
cd /root/ncal
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

Confirm the tools and runtime exist:

```sh
command -v qemu-x86_64
ls -l /usr/x86_64-linux-gnu/ld-linux-x86-64.so.2
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

AGP requires the custom executable to be named `aapt2`. Create a temporary wrapper; do not add machine-specific paths to the repository:

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

The version command should print an Android Asset Packaging Tool version. If it reports a missing `libgcc_s.so.1`, check the extracted path and `LD_LIBRARY_PATH` in the wrapper.

### 4.3 Build with the wrapper

Pass the AAPT2 override on the Gradle command line:

```sh
cd /root/ncal
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
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug \
  -Pandroid.aapt2FromMavenOverride=/tmp/ncal-aapt2/aapt2 \
  --no-daemon
```

AGP prints an experimental warning for `android.aapt2FromMavenOverride`; it is expected for this command-line-only compatibility override and does not indicate a build failure.

## 5. Confirm the generated APK

After a successful ARM64/QEMU build:

```sh
ls -lh app/build/outputs/apk/debug/app-debug.apk
sha256sum app/build/outputs/apk/debug/app-debug.apk
```

The APK produced during the verified local build was approximately 12 MB. Its checksum after the final documented verification build was:

```text
14925d7d927c008a9e0fb1995f0b8808e9cbed25c9d9f072f253dce2b4c3664d  app/build/outputs/apk/debug/app-debug.apk
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

Install `libc6-amd64-cross`, then verify `/usr/x86_64-linux-gnu/ld-linux-x86-64.so.2` exists.

### `libgcc_s.so.1: cannot open shared object file`

Install the amd64 `libgcc-s1` package or use the isolated package extraction in section 4.1. Confirm the wrapper's `LD_LIBRARY_PATH` points at the extracted `usr/lib/x86_64-linux-gnu` directory.

### Dependency verification failure

Do not disable verification for an untrusted build. Use the committed `gradle/verification-metadata.xml`, the tracked wrapper checksum, and a trusted repository. If a legitimate dependency change is being made, review and regenerate metadata intentionally rather than using a blanket bypass.

### No APK is found

Check the task result and use:

```sh
./gradlew :app:assembleDebug --info
ls -lh app/build/outputs/apk/debug/
```

A successful build must create `app/build/outputs/apk/debug/app-debug.apk`.

## 9. NixOS aarch64 session notes (2026-10-01)

This section records the issues hit when running `:app:assembleDebug` on this NixOS `aarch64` host from `/home/naresh/repo/ncal` (the `/opt/android-sdk` and `/root/ncal` paths in sections 1-4 do not exist here), and the exact workarounds used. All generated paths below are intentionally under `/tmp` so the repository stays portable.

Workspace differences:

```text
uname -m: aarch64
repo:     /home/naresh/repo/ncal
/opt/android-sdk: missing
/root/ncal:      missing
java:            not in PATH, JAVA_HOME unset
```

### 9.1 No JDK

Symptom:

```text
ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH.
```

Resolution: use the Nix-provided JDK 17 (matches `JavaVersion.VERSION_17` in `app/build.gradle.kts`):

```sh
nix build --impure --expr 'let pkgs = import <nixpkgs> {}; in pkgs.jdk17' \
  --out-link /tmp/ncal-sdk-links/jdk17 --print-out-paths
/tmp/ncal-sdk-links/jdk17/bin/java -version

export JAVA_HOME=/tmp/ncal-sdk-links/jdk17
export PATH="$JAVA_HOME/bin:$PATH"
```

### 9.2 No Android SDK

There is no preinstalled SDK. Resolution: compose one from `nixpkgs` (unfree + license acceptance required):

```sh
export NIXPKGS_ALLOW_UNFREE=1
export NIXPKGS_ACCEPT_ANDROID_SDK_LICENSE=1

nix build --impure \
  --expr 'let pkgs = import <nixpkgs> { config.allowUnfree=true; config.android_sdk.accept_license=true; }; in (pkgs.androidenv.composeAndroidPackages { platformVersions=["37"]; buildToolsVersions=["37.0.0"]; includeEmulator=false; }).androidsdk' \
  --out-link /tmp/androidsdk
```

This yields `aarch64`-hosted wrapper scripts plus an `x86-64` `build-tools/37.0.0/aapt2`, confirmed with:

```sh
nix-shell -p file --run "file /tmp/androidsdk/libexec/android-sdk/build-tools/37.0.0/aapt2"
# ELF 64-bit LSB pie executable, x86-64 ...
```

Running it directly fails as expected:

```text
exec format error: .../aapt2
```

### 9.3 x86-64 SDK binaries need QEMU plus an x86-64 sysroot

`qemu-x86_64` is not installed; Debian paths such as `/usr/x86_64-linux-gnu` do not exist on NixOS. Resolution:

```sh
nix build --impure --expr 'let pkgs = import <nixpkgs> {}; in pkgs.qemu' \
  --out-link /tmp/ncal-sdk-links/qemu --print-out-paths

nix build --impure --expr 'let pkgs = import <nixpkgs> { system="x86_64-linux"; }; in pkgs.glibc.out' \
  --out-link /tmp/ncal-sdk-links/glibc-x64 --print-out-paths

nix build --impure --expr 'let pkgs = import <nixpkgs> { system="x86_64-linux"; }; in pkgs.gcc.cc.lib' \
  --out-link /tmp/ncal-sdk-links/gcc-lib-x64 --print-out-paths

mkdir -p /tmp/ncal-sysroot/lib64 /tmp/ncal-sysroot/lib
ln -sf /tmp/ncal-sdk-links/glibc-x64/lib/ld-linux-x86-64.so.2 /tmp/ncal-sysroot/lib64/
ln -sf /tmp/ncal-sdk-links/glibc-x64/lib/* /tmp/ncal-sysroot/lib/ || true
cp -P /tmp/ncal-sdk-links/gcc-lib-x64-lib/lib/libgcc_s.so* /tmp/ncal-sysroot/lib/
cp -P /tmp/ncal-sdk-links/gcc-lib-x64-lib/lib/libgcc_s.so* /tmp/ncal-sysroot/lib64/ || true
```

Verified with:

```sh
/tmp/ncal-sdk-links/qemu/bin/qemu-x86_64 -L /tmp/ncal-sysroot \
  /tmp/androidsdk/libexec/android-sdk/build-tools/37.0.0/aapt2 version
# Android Asset Packaging Tool (aapt) 2.20-15087165
```

### 9.4 Nix store SDK is read-only; plain `cp -r` hardlinks

`cp -r /tmp/androidsdk/... /tmp/ncal-sdk/` produced the same inode as the Nix store (same device, root-owned `0555` hardlinks), so later `mv`/`chmod` failed with:

```text
Read-only file system
```

Resolution: copy with Python (`shutil.copytree`/`copyfile` following the `platform-tools`/`tools` symlinks) into a fresh writable directory:

```sh
mkdir -p /tmp/ncal-sdk-writable
# python copytree/copyfile from /tmp/androidsdk/libexec/android-sdk
# to /tmp/ncal-sdk-writable, dereferencing symlinks
chmod -R u+w /tmp/ncal-sdk-writable
```

Then wrap every needed `x86-64` ELF with QEMU (skipping `.so` libraries):

```sh
QEMU=/tmp/ncal-sdk-links/qemu/bin/qemu-x86_64
SYSROOT=/tmp/ncal-sysroot
# for each binary, e.g. aapt/aapt2/aidl/bcc_compat/dexdump/llvm-rs-cc/split-select/zipalign:
#   mv "$f" "$f.real"
#   printf '#!/bin/sh\nexec %s -L %s "%s.real" "$@"\n' "$QEMU" "$SYSROOT" "$f" > "$f"
#   chmod +x "$f"

mkdir -p /tmp/ncal-aapt2
cat > /tmp/ncal-aapt2/aapt2 <<EOF
#!/bin/sh
exec $QEMU -L $SYSROOT /tmp/ncal-sdk-writable/build-tools/37.0.0/aapt2.real "$@"
EOF
chmod 700 /tmp/ncal-aapt2/aapt2
/tmp/ncal-aapt2/aapt2 version
```

The AGP `android.aapt2FromMavenOverride` file must still be named exactly `aapt2`.

### 9.5 Strict dependency verification fails on fresh resolve

Strict build command:

```sh
export JAVA_HOME=/tmp/ncal-sdk-links/jdk17
export ANDROID_HOME=/tmp/ncal-sdk-writable
export ANDROID_SDK_ROOT=/tmp/ncal-sdk-writable
export PATH="$JAVA_HOME/bin:$PATH"

./gradlew :app:assembleDebug \
  -Pandroid.aapt2FromMavenOverride=/tmp/ncal-aapt2/aapt2 \
  --console=plain --no-daemon
```

failed during dependency verification:

```text
5 artifacts failed verification:
  - guava-parent-33.4.0-jre.pom
  - junit-bom-5.10.2.module
  - junit-bom-5.11.0-M2.module
  - kotlin-gradle-plugins-bom-2.2.10.module
  - kotlin-gradle-plugins-bom-2.2.10.pom
```

With `--dependency-verification=lenient` the build progressed further but still reported `kotlinx-coroutines-bom-1.7.3.pom` and `kotlinx-coroutines-bom-1.8.0.pom` verification warnings before succeeding. This was treated as a stale `gradle/verification-metadata.xml` versus upstream metadata issue, not a code issue. The committed metadata was left untouched; lenient mode was used only for this local run. A proper fix is to review and regenerate `gradle/verification-metadata.xml` intentionally, not to commit a bypass.

Gradle also auto-installed the missing `build-tools;36.0.0` package into the writable SDK copy during configuration.

### 9.6 Successful local command and artifact

```sh
export JAVA_HOME=/tmp/ncal-sdk-links/jdk17
export ANDROID_HOME=/tmp/ncal-sdk-writable
export ANDROID_SDK_ROOT=/tmp/ncal-sdk-writable
export PATH="$JAVA_HOME/bin:$PATH"

./gradlew :app:assembleDebug \
  -Pandroid.aapt2FromMavenOverride=/tmp/ncal-aapt2/aapt2 \
  --dependency-verification=lenient \
  --console=plain --no-daemon
```

Result:

```text
BUILD SUCCESSFUL in 3m 10s
38 actionable tasks: 38 executed
```

```sh
ls -lh app/build/outputs/apk/debug/app-debug.apk
sha256sum app/build/outputs/apk/debug/app-debug.apk
# d0927d14cde818b6a1b6fbc07f5fce5ace4a06759f9972f8db5ba8ae7b84f717  app/build/outputs/apk/debug/app-debug.apk
```

The hash differs from section 5 because source, dependencies, and toolchain differ. Always use the freshly calculated checksum for the artifact being distributed.
