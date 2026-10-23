#!/usr/bin/env bash
#
# Source this to build APKs on this host:
#   . apk-build/env.sh
#   sh gradlew :app:assembleDebug $MCQ_AAPT2_OVERRIDE --no-daemon
#
# It exports JAVA_HOME / ANDROID_HOME / ANDROID_SDK_ROOT / MCQ_AAPT2_OVERRIDE
# and runs apk-build/setup-env.sh first if the environment is missing or was
# lost to a reboot.

ENV_DIR="${MCQ_APK_ENV_DIR:-/tmp/mcq-apk-env}"
SDK="${MCQ_ANDROID_SDK:-$HOME/android-sdk}"

_here="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" && pwd)"

if [ ! -x "$ENV_DIR/aapt2/aapt2" ] || [ ! -x "$ENV_DIR/jdk17/bin/java" ]; then
  echo "env.sh: environment missing — running setup-env.sh"
  sh "$_here/setup-env.sh"
fi

export JAVA_HOME="$ENV_DIR/jdk17"
export ANDROID_HOME="$SDK"
export ANDROID_SDK_ROOT="$SDK"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"

# Pass unquoted on the gradlew command line; empty on native-x86 hosts.
export MCQ_AAPT2_OVERRIDE="-Pandroid.aapt2FromMavenOverride=$ENV_DIR/aapt2/aapt2"

echo "mcq env: JAVA_HOME=$JAVA_HOME"
echo "mcq env: ANDROID_HOME=$ANDROID_HOME"
echo "mcq env: MCQ_AAPT2_OVERRIDE=$MCQ_AAPT2_OVERRIDE"
