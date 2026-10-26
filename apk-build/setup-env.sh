#!/usr/bin/env bash
#
# Create or repair the APK build environment on a NixOS aarch64 host.
#
# Replaces the ad-hoc /tmp/ncal-* layout: everything now lives under one
# directory (/tmp/mcq-apk-env by default) and the script is idempotent —
# existing pieces are kept, missing pieces are rebuilt from the Nix cache,
# so after a reboot it simply re-links them in seconds.
#
# What it provides:
#   $ENV_DIR/jdk17        OpenJDK 17 (JAVA_HOME)
#   $ENV_DIR/qemu         qemu-x86_64, to run the SDK's x86-64 build tools
#   $ENV_DIR/glibc-x64    x86-64 glibc for the emulator's -L prefix
#   $ENV_DIR/gcc-libgcc-x64  x86-64 libgcc_s
#   $ENV_DIR/sysroot      the -L prefix assembled from the two above
#   $ENV_DIR/aapt2/aapt2  the AGP override wrapper (name must be exactly aapt2)
#
# Tunables: MCQ_APK_ENV_DIR, MCQ_ANDROID_SDK, MCQ_BUILD_TOOLS.
# The Android SDK itself is NOT created here — it must already exist
# (default $HOME/android-sdk, build-tools 37.0.0).

set -euo pipefail

ENV_DIR="${MCQ_APK_ENV_DIR:-/tmp/mcq-apk-env}"
SDK="${MCQ_ANDROID_SDK:-$HOME/android-sdk}"
BT="${MCQ_BUILD_TOOLS:-37.0.0}"
AAPT2="$SDK/build-tools/$BT/aapt2"

die() { echo "setup-env: $*" >&2; exit 1; }

[ -x "$AAPT2" ] || die "no aapt2 at $AAPT2 — set MCQ_ANDROID_SDK / MCQ_BUILD_TOOLS"

nix_link() { # nix_link <stable-name> <expr>
  # --no-link: nix suffixes out-links for non-default outputs (x-libgcc);
  # we print the store path and make our own predictably-named link instead.
  local out
  out="$(nix build --impure --expr "$2" --no-link --print-out-paths | head -n1)"
  ln -sfn "$out" "$ENV_DIR/$1"
  echo "  $1 -> $out"
}

mkdir -p "$ENV_DIR"
echo "mcq apk env dir: $ENV_DIR"

echo "Checking pieces:"
[ -x "$ENV_DIR/jdk17/bin/java" ] || nix_link jdk17 \
  'let pkgs = import <nixpkgs> {}; in pkgs.jdk17'
[ -x "$ENV_DIR/qemu/bin/qemu-x86_64" ] || nix_link qemu \
  'let pkgs = import <nixpkgs> {}; in pkgs.qemu'
[ -e "$ENV_DIR/glibc-x64/lib/ld-linux-x86-64.so.2" ] || nix_link glibc-x64 \
  'let pkgs = import <nixpkgs> { system="x86_64-linux"; }; in pkgs.glibc.out'
[ -e "$ENV_DIR/gcc-libgcc-x64/lib/libgcc_s.so.1" ] || nix_link gcc-libgcc-x64 \
  'let pkgs = import <nixpkgs> { system="x86_64-linux"; }; in pkgs.gcc.cc.libgcc'

# The -L prefix qemu uses to resolve the x86-64 binary's loader and libs.
SYSROOT="$ENV_DIR/sysroot"
mkdir -p "$SYSROOT/lib64" "$SYSROOT/lib"
ln -sf "$ENV_DIR/glibc-x64/lib/ld-linux-x86-64.so.2" "$SYSROOT/lib64/"
ln -sf "$ENV_DIR/glibc-x64/lib/"* "$SYSROOT/lib/"
cp -Pf "$ENV_DIR/gcc-libgcc-x64/lib/libgcc_s.so"* "$SYSROOT/lib/"
cp -Pf "$ENV_DIR/gcc-libgcc-x64/lib/libgcc_s.so"* "$SYSROOT/lib64/"

# AGP requires the override file to be named exactly aapt2.
mkdir -p "$ENV_DIR/aapt2"
cat > "$ENV_DIR/aapt2/aapt2" <<EOF
#!/bin/sh
exec "$ENV_DIR/qemu/bin/qemu-x86_64" -L "$SYSROOT" "$AAPT2" "\$@"
EOF
chmod 700 "$ENV_DIR/aapt2/aapt2"

VERSION="$("$ENV_DIR/aapt2/aapt2" version 2>&1)"
echo "aapt2 wrapper OK: $VERSION"
echo "Done. Source apk-build/env.sh to use it."
